/*******************************************************************************
 * Copyright (c) 2009 Casey Marshal and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors: 
 *    Casey Marshal - initial API and implementation
 *******************************************************************************/
package ccw.console;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.graphics.Color;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.console.IConsoleView;
import org.eclipse.ui.console.IOConsole;
import org.eclipse.ui.console.IOConsoleInputStream;
import org.eclipse.ui.console.IOConsoleOutputStream;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.part.IPageBookViewPage;
import org.eclipse.ui.texteditor.ChainedPreferenceStore;

import ccw.CCWPlugin;
import clojure.lang.Compiler;
import clojure.lang.LineNumberingPushbackReader;
import clojure.lang.LispReader;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;

public class ClojureConsole extends IOConsole implements Runnable {
    final static Color OUTPUT_COLOUR = new Color(null, 0, 0, 255); // blue
    final static Color ERROR_COLOUR = new Color(null, 255, 0, 0); // red
    final static Color INPUT_COLOUR = new Color(null, 0, 180, 180); // cyan

    static final Symbol USER = Symbol.create("user");
    static final Symbol CLOJURE = Symbol.create("clojure.core");
    static final Var in_ns = RT.var("clojure.core", "in-ns");
    static final Var refer = RT.var("clojure.core", "refer");
    static final Var ns = RT.var("clojure.core", "*ns*");
    static final Var compile_path = RT.var("clojure.core", "*compile-path*");
    static final Var warn_on_reflection = RT.var("clojure.core", "*warn-on-reflection*");
    static final Var print_meta = RT.var("clojure.core", "*print-meta*");
    static final Var print_length = RT.var("clojure.core", "*print-length*");
    static final Var print_level = RT.var("clojure.core", "*print-level*");
    static final Var star1 = RT.var("clojure.core", "*1");
    static final Var star2 = RT.var("clojure.core", "*2");
    static final Var star3 = RT.var("clojure.core", "*3");
    static final Var stare = RT.var("clojure.core", "*e");
    static final Var outVar = RT.var("clojure.core", "*out*");

    private PrintStream out;
    private PrintStream info;
    private PrintStream err;

    IOConsoleInputStream in;

    IOConsoleOutputStream ioOut;
    IOConsoleOutputStream ioInfo;
    IOConsoleOutputStream ioErr;

    
    private BlockingQueue queue = new LinkedBlockingQueue();
    
    private IPreferenceStore preferenceStore;

    public ClojureConsole() {
        super("Clojure REPL", null);
        preferenceStore = createCombinedPreferenceStore();
        Thread evalThread = new Thread(this);
        evalThread.start();
    }

    public void evalFile(IFile file) {
        try {
            queue.put(file);
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void evalString(String string) {
    	if (in != null) {
    		in.appendData(string);
    		try {
    			if (ioInfo != null) {
    				ioInfo.write(string);
    				String LINE_SEPARATOR = System.getProperty("line.separator"); 
    				if (!string.endsWith(LINE_SEPARATOR)) {
    					ioInfo.write(LINE_SEPARATOR);
    				}
    			}
			} catch (IOException e) {
			}
    	}
    }

    public void run() {
        in = getInputStream();
        ioOut = newOutputStream();
        ioInfo = newOutputStream();
        ioErr = newOutputStream();
        in.setColor(INPUT_COLOUR);
        ioOut.setColor(OUTPUT_COLOUR);
        ioInfo.setColor(INPUT_COLOUR);
        ioErr.setColor(ERROR_COLOUR);
        out = new PrintStream(ioOut);
        info = new PrintStream(ioInfo);
        err = new PrintStream(ioErr);

        try {
    		//*ns* must be thread-bound for in-ns to work
    		//thread-bind *warn-on-reflection* so it can be set!
    		//thread-bind *1,*2,*3,*e so each repl has its own history
    		//must have corresponding popThreadBindings in finally clause
    		Var.pushThreadBindings(
    				RT.map(ns, ns.get(),
    				       warn_on_reflection, warn_on_reflection.get(),
    				       print_meta, print_meta.get(),
    				       print_length, print_length.get(),
    				       print_level, print_level.get(),
    				       compile_path, "classes",
    				       star1, null,
    				       star2, null,
    				       star3, null,
    				       stare, null,
    				       outVar, new PrintWriter(out)));

    		//create and move into the user namespace
    		in_ns.invoke(USER);
    		refer.invoke(CLOJURE);

            // repl IO support
            final LineNumberingPushbackReader rdr = new LineNumberingPushbackReader(
                    new InputStreamReader(in, RT.UTF8));
            final Object EOF = new Object();

            new Thread(new Runnable() {

                public void run() {
                    for (;;) {
                        try {
                            Object r = LispReader.read(rdr, false, EOF, false);
                            queue.put(r);
                        }
                        catch (Throwable e) {
                            Throwable c = e;
                            while (c.getCause() != null) {
                                c = c.getCause();
                            }
                            err.println(c);
                            e.printStackTrace(err);
                        }
                    }
                }

            }).start();

            OutputStreamWriter w = new OutputStreamWriter(out);// (OutputStreamWriter)
                                                               // RT.OUT.get();//
                                                               // new
            // OutputStreamWriter(System.out);

            // start the loop
            w.write("Clojure\n");
            for (;;) {
                try {
    				w.write("=> "); // TODO HAVE NS
    				w.flush();

                    Object r = queue.take();
                    Object ret;
                    if (r instanceof IFile) {
                        ret = loadFile((IFile) r);
                    }
                    else {
                        ret = eval(r);
                    }

                    RT.print(ret, w);
                    w.write('\n');
                    w.flush();
    				star3.set(star2.get());
    				star2.set(star1.get());
    				star1.set(ret);
                }
                catch (Throwable e) {
                    Throwable c = e;
                    while (c.getCause() != null) {
                        c = c.getCause();
                    }
                    err.println(e instanceof Compiler.CompilerException ? e : c);
//                    e.printStackTrace(err);
                    stare.set(e);
                }
            }
        }
        catch (Exception e) {
            e.printStackTrace((PrintWriter) RT.ERR.get());
        }
        finally {
            Var.popThreadBindings();
        }
    }

    private Object loadFile(IFile file) throws Exception {
        String fullPath = file.getLocation().toFile().toString();
        info.println("(load-file \"" + fullPath + "\")");
        return clojure.lang.Compiler.loadFile(fullPath);
    }

    private Object eval(Object r) throws Exception {
        return clojure.lang.Compiler.eval(r);
    }

    /* (non-Javadoc)
     * @see org.eclipse.ui.console.IConsole#createPage(org.eclipse.ui.console.IConsoleView)
     */
    public IPageBookViewPage createPage(IConsoleView view) {
        return new ClojureConsolePage(this, view, preferenceStore);
    }

    /**
     * Create a preference store combined from the Clojure, the EditorsUI and
     * the PlatformUI preference stores to inherit all the default text editor
     * settings from the Eclipse preferences.
     * 
     * @return the combined preference store.
     */
    private IPreferenceStore createCombinedPreferenceStore() {
        List<IPreferenceStore> stores = new LinkedList<IPreferenceStore>();
        stores.add(CCWPlugin.getDefault().getPreferenceStore());
        stores.add(EditorsUI.getPreferenceStore());
        stores.add(PlatformUI.getPreferenceStore());
        return new ChainedPreferenceStore(stores.toArray(new IPreferenceStore[stores.size()]));
    }
}
