/*******************************************************************************
 * Copyright (c) 2009 Anyware Technologies and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Gaetan Morice (Anyware Technologies) - initial implementation
 *******************************************************************************/

package ccw.builder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.ui.texteditor.MarkerUtilities;

import ccw.CCWPlugin;
import ccw.debug.ClojureClient;
import ccw.editors.antlrbased.CompileLibAction;


public class ClojureVisitor implements IResourceVisitor {
	private Map.Entry<IFolder, IFolder> currentSrcFolder;
	
	private Map<IFolder, IFolder> srcFolders;
	
	private static final String CLOJURE_EXTENSION = "clj";
	private final List<String> clojureLibs = new ArrayList<String>();
	private final ClojureClient clojureClient;
	
	public ClojureVisitor() {
		clojureClient = null;
	}
	
	public ClojureVisitor(ClojureClient clojureClient) {
		this.clojureClient = clojureClient;
	}
	public void visit (Map<IFolder, IFolder> srcFolders) throws CoreException {
		this.srcFolders = new HashMap<IFolder, IFolder>(srcFolders);
        for(Map.Entry<IFolder, IFolder> srcFolderEntry : srcFolders.entrySet()){
        	setSrcFolder(srcFolderEntry);
            srcFolderEntry.getKey().accept(this);
        }
		if (clojureClient!=null) {
			for (String maybeLibName: clojureLibs) {
				System.out.println("compiling:'" + maybeLibName + "'");
				Map<?,?> result = (Map<?,?>) clojureClient.remoteLoadRead(CompileLibAction.compileLibCommand(maybeLibName));
				System.out.println("compile result:" + result);
				if (result != null) {
					//response-type" -1, "response" "[{\"file-name\" \"Compiler.java\", \"line-number\" 4186, \"message\
					if (Integer.valueOf(-1).equals(result.get("response-type"))) {
						Collection<?> response = (Collection<?>) result.get("response");
						if (response != null) {
							Map<?,?> errorMap = (Map<?,?>) response.iterator().next();
							if (errorMap != null) {
								String message = (String) errorMap.get("message");
								if (message != null) {
									System.out.println("error message:" + message);
									Matcher matcher = ERROR_MESSAGE_PATTERN.matcher(message);
									if (matcher.matches()) {
										System.out.println("match found for message:'" + message + "'");
										String messageBody = matcher.group(MESSAGE_GROUP);
										String filename = matcher.group(FILENAME_GROUP);
										String lineStr = matcher.group(LINE_GROUP);
										System.out.println("message:" + messageBody);
										System.out.println("file:" + filename);
										System.out.println("line:" + lineStr);
										if (!NO_SOURCE_FILE.equals(filename)) {
											createMarker(filename, Integer.parseInt(lineStr), messageBody);
										}
									} else {
										System.out.println("no match found for message:'" + message + "'");
									}
								}
							}
						}
					}
				}
			}
		}
	}
	
	//"java.lang.Exception: Unable to resolve symbol: pairs in this context (sudoku_solver.clj:130)"
	private static final Pattern ERROR_MESSAGE_PATTERN = Pattern.compile("^(java.lang.Exception: )?(.*)\\((.+):(\\d+)\\)$");
	private static final int MESSAGE_GROUP = 2;
	private static final int FILENAME_GROUP = 3;
	private static final int LINE_GROUP = 4;
	private static final String NO_SOURCE_FILE = "NO_SOURCE_FILE";
	
	public boolean visit(IResource resource) throws CoreException {
		if(resource instanceof IFile) {
			IFile file = (IFile) resource;
			String extension = file.getFileExtension();
			if(extension != null && extension.equals(CLOJURE_EXTENSION)){
				// Find corresponding library name
				IPath maybeLibPath = file.getFullPath().removeFirstSegments(currentSrcFolder.getKey().getFullPath().segmentCount()).removeFileExtension();
				String maybeLibName = maybeLibPath.toString().replace('/', '.').replace('_', '-');
				System.out.println("maybelibpath:'" + maybeLibName + "'");
				clojureLibs.add(maybeLibName);
			}
		}
		return true;
	}
	
	private void createMarker(final String filename, final int line, final String message) {
		try {
			System.out.println("(trying to) create a marker for " + filename);
			for (IFolder srcFolder: srcFolders.keySet()) {
				srcFolder.accept(new IResourceVisitor() {
					public boolean visit(IResource resource) throws CoreException {
						if (resource.getType() == IResource.FILE) {
							System.out.println("    file found: " + resource.getName());
							if (resource.getName().equals(filename)) {
								Map attrs = new HashMap();
								MarkerUtilities.setLineNumber(attrs, line);
								MarkerUtilities.setMessage(attrs, message);
								attrs.put(IMarker.SEVERITY, IMarker.SEVERITY_ERROR);
								MarkerUtilities.createMarker(resource, attrs, ClojureBuilder.CLOJURE_COMPILER_PROBLEM_MARKER_TYPE);
								
								System.out.println("created marker !");
							}
						}
						return true;
					}
				});
			}
		} catch (CoreException e) {
			CCWPlugin.logError("error while creating marker for file : " + filename + " at line " + line 
					+ " with message :'" + message + "'", e);
		}
	}
	
	public String[] getClojureLibs() {
		return clojureLibs.toArray(new String[clojureLibs.size()]);
	}

	/**
	 * @param srcFolder
	 */
	public void setSrcFolder(Map.Entry<IFolder, IFolder> srcFolder) {
		this.currentSrcFolder = srcFolder;
	}

}
