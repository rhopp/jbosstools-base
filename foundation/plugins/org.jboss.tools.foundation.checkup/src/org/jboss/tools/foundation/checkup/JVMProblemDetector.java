/******************************************************************************* 
 * Copyright (c) 2015 Red Hat, Inc. 
 * Distributed under license by Red Hat, Inc. All rights reserved. 
 * This program is made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, 
 * and is available at http://www.eclipse.org/legal/epl-v10.html 
 * 
 * Contributors: 
 * Red Hat, Inc. - initial API and implementation 
 ******************************************************************************/ 
package org.jboss.tools.foundation.checkup;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.LinkedBlockingDeque;

import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.osgi.internal.messages.Msg;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.internal.views.log.LogEntry;
import org.eclipse.ui.internal.views.log.LogSession;
import org.eclipse.ui.internal.views.log.TailInputStream;
import org.eclipse.ui.progress.UIJob;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.service.prefs.BackingStoreException;


public class JVMProblemDetector implements IStartup, ILogListener{
	private static final String PREFERENCE_NAME="org.jboss.tools.foundation.checkup.JVMProblemDetector_NOT_TO_SHOW"; //$NON-NLS-1$
	
	// org.osgi.framework.BundleException: Could not resolve module: 
	private static final String BUNDLE_EXCEPTION_STRING = BundleException.class.getCanonicalName()+": "+Msg.Module_ResolveError; //$NON-NLS-1$
	
	// -> Bundle-SymbolicName:
	private static final String BUNDLE_SYMBOLICNAME_STRING = "-> "+Constants.BUNDLE_SYMBOLICNAME+":"; //$NON-NLS-1$
	
	// Unresolved requirement: Require-Capability: : osgi.ee; filter:=\"(&(osgi.ee=
	private static final String UNRESOLVED_REQUIREMENT_STRING = Msg.ModuleResolutionReport_UnresolvedReq+Constants.REQUIRE_CAPABILITY + ": osgi.ee; filter:=\"(&(osgi.ee="; //$NON-NLS-1$
	private static final String VERSION_STRING = ")(version="; //$NON-NLS-1$
	
	// Caused by: java.lang.UnsupportedClassVersionError: org/jboss/tools/usage/event/UsageEventType : Unsupported major.minor version 52.0
	private static final String UNSUPPORTED_CLASS_VERSION_STRING = UnsupportedClassVersionError.class.getCanonicalName()+": ";//$NON-NLS-1$
	
	private static final String UNSUPPORTED_MAJOR_MINOR_VERSION_STRING = ": Unsupported major.minor version";//$NON-NLS-1$
	
	private static HashMap<String, String> majorMinorVersions = new HashMap<String, String>();
	
	static{
		majorMinorVersions.put("45.3", "1.1");
		majorMinorVersions.put("46.0", "1.2");
		majorMinorVersions.put("47.0", "1.3");
		majorMinorVersions.put("48.0", "1.4");
		majorMinorVersions.put("49.0", "1.5");
		majorMinorVersions.put("50.0", "1.6");
		majorMinorVersions.put("51.0", "1.7");
		majorMinorVersions.put("52.0", "1.8");
	}
	
	private static final long MAX_LENGTH = 1024 * 1024;
	
	// in order to collect more data to show we do not show the warning dialog immediately 
	// time to wait before show the warning dialog in milliseconds
	private static final long WAIT_TIME_AFTER_READING_LOG = 7000;
	private static final long WAIT_TIME_AFTER_EVENT = 3000;
	
	private UnresolvedStructure structure = new UnresolvedStructure();
	
	private LinkedBlockingDeque<String> queue = new LinkedBlockingDeque<String>();
	
	private String javaVersion = null;
	
	private boolean testEnvironment = false;
	
	private static JVMProblemDetector instance = null;
	
	public JVMProblemDetector(){
		super();
		instance = this;
	}
	
	private JVMProblemDetectorJob job = null;
	
	public void earlyStartup() {
		if(isAllowedToShow()){
			javaVersion = System.getProperty("java.version"); //$NON-NLS-1$
			
			// listen log
			Platform.addLogListener(JVMProblemDetector.this);

			// read error log
			
			File logFile = Platform.getLogFileLocation().toFile();
			if (logFile != null && logFile.exists()){
				try {
					readLogFile(new TailInputStream(logFile, MAX_LENGTH));
				} catch (IOException e) {
					FoundationCheckupPlugin.logError(e);
				}
			}
			
			eclipseStartTime = 0;

			// start job which read the queue
			job = new JVMProblemDetectorJob();
			job.schedule();
		}
	}
	
	public static void cancelJob(){
		if(instance != null){
			if(instance.job != null){
				instance.job.cancel();
				instance.job = null;
			}
		instance = null;
		}
	}
	
	class JVMProblemDetectorJob extends Job{

		public JVMProblemDetectorJob() {
			super(JVMProblemDetectorMessages.JOB_TITLE);
			setSystem(true);
			setPriority(LONG);
		}

		@Override
		protected IStatus run(IProgressMonitor monitor) {
			readQueue();
			
			Status status = new Status(Status.OK, FoundationCheckupPlugin.PLUGIN_ID, "");
			return status;
		}
		
	}
	
	/**
	 * for test purpose 
	 * @return
	 */
	public static UnresolvedStructure getUnresolvedStructure(){
		if(instance == null){
			instance = new JVMProblemDetector();
		}
		return instance.structure;
	}
	
	/**
	 * changed to public for test purpose
	 * @param InputStream
	 */
	public void readLogFile(InputStream stream) {
			
		BufferedReader reader = null;
		try{
			reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));  //$NON-NLS-1$
			while (true) {
				String line0 = reader.readLine();
				if (line0 == null)
					break;
				String line = line0.trim();
				
				scanLine(line);
			}
		} catch (FileNotFoundException e) {
			FoundationCheckupPlugin.logError(e);
		} catch (IOException e) {
			FoundationCheckupPlugin.logError(e);
		}finally{
			try {
				if(reader != null){
					reader.close();
				}
			} catch (IOException e) {
				// do nothing
			}
		}
		
		structure.report(WAIT_TIME_AFTER_READING_LOG);
	}
	
	private void readQueue(){
		
		try{
			while(isAllowedToShow() && !queue.isEmpty()){
				String message = queue.take();
				BufferedReader reader = null;
				try{
					reader = new BufferedReader(new StringReader(message)); 
					while (true) {
						String line0 = reader.readLine();
						if (line0 == null)
							break;
						String line = line0.trim();
						scanLine(line);
					}
				}finally{
					if(reader != null){
						reader.close();
					}
				}
				structure.report(WAIT_TIME_AFTER_EVENT);
			}
		} catch (IOException e){
			FoundationCheckupPlugin.logError(e);
		} catch (InterruptedException e) {
			// do nothing
		}
	}
	
	// temporary storage
	private List<String> moduleNameList = new ArrayList<String>();
	private String currentModuleName = null;

	private void scanLine(String line){
		if (line.startsWith(LogSession.SESSION)) {
			Date date = getSessionDate(line);
			if(date != null){
				currentDate = date;
			}
			// clear previous data
			// call from NOT UI Thread
			structure.clear();
			moduleNameList.clear();
			currentModuleName = null;
		} else if (line.startsWith("!ENTRY")) {
			Date date = getEntryDate(line);
			if(date != null){
				currentDate = date;
			}
		} else if(line.startsWith(BUNDLE_EXCEPTION_STRING)){
			if(isInCurrentSession()){
				// parse unresolved module
				int position = line.indexOf("[");
				String unresolvedModule;
				if(position > 0){
					unresolvedModule = line.substring(BUNDLE_EXCEPTION_STRING.length(), position).trim();
				}else{
					unresolvedModule = line.substring(BUNDLE_EXCEPTION_STRING.length()).trim();
				}
				
				moduleNameList.clear();
				currentModuleName = unresolvedModule;
			}
		} else if(line.startsWith(BUNDLE_SYMBOLICNAME_STRING)){
			if(isInCurrentSession()){
				// parse unresolved module
				int position = line.indexOf(";");
				String unresolvedModule;
				if(position > 0){
					unresolvedModule = line.substring(BUNDLE_SYMBOLICNAME_STRING.length(), position).trim();
				}else{
					unresolvedModule = line.substring(BUNDLE_SYMBOLICNAME_STRING.length()).trim();
				}
				
				if(currentModuleName != null && !moduleNameList.contains(currentModuleName)){
					moduleNameList.add(currentModuleName);
				}
				currentModuleName = unresolvedModule;
			}
		} else if(line.startsWith(UNRESOLVED_REQUIREMENT_STRING)){
			if(isInCurrentSession()){
				// parse Java name and version
				int position = line.indexOf(VERSION_STRING);
				if(position > 0){
					int endPosition = line.indexOf(")", position+VERSION_STRING.length());
					
					String javaName = line.substring(UNRESOLVED_REQUIREMENT_STRING.length(), position).trim();
					String javaVersion;
					if(endPosition > 0){
						javaVersion = line.substring(position+VERSION_STRING.length(), endPosition).trim();
					}else{
						javaVersion = line.substring(position+VERSION_STRING.length()).trim();
					}
					// call from NOT UI Thread
					// store unresolved module
					structure.addRequieredJava(currentModuleName, moduleNameList, javaName, javaVersion);
				}
			}
		}else if(line.indexOf(UNSUPPORTED_CLASS_VERSION_STRING) >= 0){
			if(isInCurrentSession()){
				int position = line.indexOf(UNSUPPORTED_CLASS_VERSION_STRING);
				int versionPosition = line.indexOf(UNSUPPORTED_MAJOR_MINOR_VERSION_STRING);
				if(versionPosition >= 0){
					String className = line.substring(position+UNSUPPORTED_CLASS_VERSION_STRING.length(), versionPosition).trim();
					String majorMinorVersion = line.substring(versionPosition+UNSUPPORTED_MAJOR_MINOR_VERSION_STRING.length()).trim();
					String javaVersion = majorMinorVersions.get(majorMinorVersion);
					if(javaVersion != null){
						structure.addUnresolvedClass(className, javaVersion);
					}
				}
			}
		}
	}
	
	private boolean isAllowedToShow(){
		return InstanceScope.INSTANCE.getNode(FoundationCheckupPlugin.PLUGIN_ID).getBoolean(PREFERENCE_NAME, true) || testEnvironment;
	}
	
	private void setAllowedToShow(boolean allowedToShow){
		if(!allowedToShow){
			Platform.removeLogListener(JVMProblemDetector.this);
		}
		IEclipsePreferences ep = InstanceScope.INSTANCE.getNode(FoundationCheckupPlugin.PLUGIN_ID);
		try {
			ep.putBoolean(PREFERENCE_NAME, allowedToShow);
			ep.flush();
		} catch (BackingStoreException e) {
			FoundationCheckupPlugin.logError(e);
		}
	}
	
	private void processStatus(IStatus status){
		Throwable exception = status.getException();
		String message = status.getMessage();
		if(message != null){
			try {
				queue.put(message);
			} catch (InterruptedException e) {
				// do nothing
			}
		}
		if(exception != null){
			message = exception.getMessage();
			if(message != null){
				try {
					queue.put(message);
				} catch (InterruptedException e) {
					// do nothing
				}
			}
			for(IStatus child : status.getChildren()){
				processStatus(child);
			}
		}
	}

	public void logging(IStatus status, String plugin) {
		processStatus(status);
		if(job != null){
			job.schedule();
		}
	}
	
	class ReportJob extends UIJob{
		
		public ReportJob() {
			super(JVMProblemDetectorMessages.SHOW_WARNING_DIALOG_JOB_TITLE);
		}

		public IStatus runInUIThread(IProgressMonitor monitor) {
			if(!UnresolvedModulesDialog.showing){
				// call from UI Thread
				List<UnresolvedModule> modules = structure.getUnresolvedModules();
				
				if(modules.size() > 0){
					UnresolvedModulesDialog dialog = new UnresolvedModulesDialog(Display.getDefault().getActiveShell(), modules, javaVersion);
					dialog.open();
					setAllowedToShow(dialog.showNextTime());
				}
			}
			if(!UnresolvedClassesDialog.showing){
				// call from UI Thread
				List<UnresolvedClass> classes = structure.getUnresolvedClasses();
				
				if(classes.size() > 0){
					UnresolvedClassesDialog dialog = new UnresolvedClassesDialog(Display.getDefault().getActiveShell(), classes, javaVersion);
					dialog.open();
					setAllowedToShow(dialog.showNextTime());
				}
			}
			Status status = new Status(Status.OK, FoundationCheckupPlugin.PLUGIN_ID, "");
			return status;
		}
	}

	public class UnresolvedStructure{
		private List<UnresolvedModule> unresolvedModuleList = new ArrayList<UnresolvedModule>();
		
		private List<UnresolvedClass> unresolvedClassList = new ArrayList<UnresolvedClass>();
		
		/**
		 * returns copy of list of unresolved module
		 * supposed to be called from UI Thread
		 * @return
		 */
		public List<UnresolvedModule> getUnresolvedModules(){
			synchronized(unresolvedModuleList){
				List<UnresolvedModule> list = new ArrayList<UnresolvedModule>(unresolvedModuleList);
				if(!testEnvironment){
					unresolvedModuleList.clear();
				}
				return list;
			}
		}

		/**
		 * returns copy of list of unresolved classes
		 * supposed to be called from UI Thread
		 * @return
		 */
		public List<UnresolvedClass> getUnresolvedClasses(){
			synchronized(unresolvedClassList){
				List<UnresolvedClass> list = new ArrayList<UnresolvedClass>(unresolvedClassList);
				if(!testEnvironment){
					unresolvedClassList.clear();
				}
				return list;
			}
		}
		

		public boolean isNeedReport(){
			if(testEnvironment){
				return false;
			}
			synchronized(unresolvedModuleList){
				if( unresolvedModuleList.size() > 0)
					return true;
			}
			synchronized(unresolvedClassList){
				return unresolvedClassList.size() > 0;
			}
		}
		
		public void clear(){
			synchronized(unresolvedModuleList){
				unresolvedModuleList.clear();
			}
			synchronized(unresolvedClassList){
				unresolvedClassList.clear();
			}
		}
		
		public void addUnresolvedClass(String className, String javaVersion){
			synchronized(unresolvedClassList){
				UnresolvedClass unresolvedClass = new UnresolvedClass(className, javaVersion);
				
				if(!unresolvedClassList.contains(unresolvedClass)){
					unresolvedClassList.add(unresolvedClass);
				}
			}

		}
		
		
		public void addRequieredJava(String moduleName, List<String> moduleNameList, String javaName, String javaVersion){
			if(moduleName == null){
				return;
			}
			
			synchronized(unresolvedModuleList){
				UnresolvedModule unresolvedModule = new UnresolvedModule(moduleName, javaName, javaVersion);
				
				if(unresolvedModuleList.contains(unresolvedModule)){
					for(UnresolvedModule module : unresolvedModuleList){
						if(module.equals(unresolvedModule)){
							unresolvedModule = module;
							break;
						}
					}
				}else{
					unresolvedModuleList.add(unresolvedModule);
				}
				
				DependantList dependantList = unresolvedModule.getDependantList();
				for(String name : moduleNameList){
					Dependant dep = new Dependant(unresolvedModule, name);
					dependantList.add(dep);
				}
				
				moduleNameList.clear();
			}
		}
		
		public void report(long time){
			if(isNeedReport()){
				// show dialog window
				ReportJob job = new ReportJob();
				job.schedule(time);
			}
		}
	}
	
	public class UnresolvedModule{
		private String name;
		private String javaName;
		private String javaVersion;
		
		private DependantList list;
		
		public UnresolvedModule(String name, String javaName, String javaVersion){
			this.name = name;
			this.javaName = javaName;
			this.javaVersion = javaVersion;
			list = new DependantList(this);
		}
		
		public DependantList getDependantList(){
			return list;
		}
		
		public String toString(){
			return NLS.bind(JVMProblemDetectorMessages.UNRESOLVED_MODULE_LABEL, new Object[]{name, javaName, javaVersion});
		}

		public boolean equals(Object o) {
			if(o instanceof UnresolvedModule){
				return o.toString().equals(toString());
			}
			return super.equals(o);
		}

		public int hashCode() {
			return toString().hashCode();
		}
	}
	
	
	
	public class DependantList{
		private UnresolvedModule module;
		// list of Dependant
		private List<Dependant> dependants = new ArrayList<Dependant>();
		
		public DependantList(UnresolvedModule module){
			this.module = module;
		}
		
		public UnresolvedModule getUnresolvedModule(){
			return module;
		}
		
		public String toString(){
			return module.toString()+" "+JVMProblemDetectorMessages.DEPENDANT_MODULES;
		}
		
		public List<Dependant> getDependants(){
			return dependants;
		}
		
		
		public void add(Dependant dependant){
			synchronized(dependants){
				if(!dependants.contains(dependant)){
					dependants.add(dependant);
				}
			}
		}
		
		public boolean equals(Object o) {
			if(o instanceof Dependant){
				return o.toString().equals(toString());
			}
			return super.equals(o);
		}
		
		public int hashCode() {
			return toString().hashCode();
		}
	}
	
	public class Dependant{
		private UnresolvedModule module;
		private String name;
		
		public Dependant(UnresolvedModule module, String name){
			this.name = name;
			this.module = module;
		}
		
		public UnresolvedModule getParent(){
			return module;
		}
		
		public String toString(){
			return name;
		}
		
		public boolean equals(Object o) {
			if(o instanceof Dependant){
				return o.toString().equals(toString());
			}
			return super.equals(o);
		}
		
		public int hashCode() {
			return toString().hashCode();
		}
	}
	
	public class UnresolvedClass{
		private String name;
		private String javaVersion;
		
		
		public UnresolvedClass(String name, String javaVersion){
			this.name = name;
			this.javaVersion = javaVersion;
		}
		
		
		public String toString(){
			return NLS.bind(JVMProblemDetectorMessages.UNRESOLVED_CLASS_LABEL, new Object[]{name, javaVersion});
		}

		public boolean equals(Object o) {
			if(o instanceof UnresolvedModule){
				return o.toString().equals(toString());
			}
			return super.equals(o);
		}

		public int hashCode() {
			return toString().hashCode();
		}
	}

	private long eclipseStartTime = getEclipseStartTime();
	private static final DateFormat DATE_FORMATTER = new SimpleDateFormat(LogEntry.F_DATE_FORMAT);
	private Date currentDate = null;
	
	/**
	 * for test purpose
	 * @param date string in yyyy-MM-dd HH:mm:ss.SSS 
	 * @throws ParseException
	 */
	public void setEclipseStartTime(String str) throws ParseException{
		eclipseStartTime = DATE_FORMATTER.parse(str).getTime();
	}
	
	private long getEclipseStartTime() {
		String ts = System.getProperty("eclipse.startTime"); //$NON-NLS-1$
		try {
			return Long.parseLong(ts);
		} catch (NumberFormatException e) {
			FoundationCheckupPlugin.logError(e);
		}
		return 0;
	}
	
	private boolean isInCurrentSession(){
		return currentDate != null && currentDate.getTime() >= eclipseStartTime;
	}
	
	private Date getSessionDate(String line) {
		line = line.substring(LogSession.SESSION.length()).trim(); // strip "!SESSION "
		int delim = line.indexOf("----"); //$NON-NLS-1$
		if (delim == -1) {
			return null;
		}
		String dateBuffer = line.substring(0, delim).trim();
		try {
			return DATE_FORMATTER.parse(dateBuffer);
		} catch (ParseException e) {
			FoundationCheckupPlugin.logError(e);
		}
		return null;
	}
	
	private Date getEntryDate(String line){
		//!ENTRY <pluginID> <severity> <code> <date>
		//!ENTRY <pluginID> <date> if logged by the framework!!!
		StringTokenizer stok = new StringTokenizer(line, LogEntry.SPACE);
		StringBuffer dateBuffer = new StringBuffer();
		int tokens = stok.countTokens();
		String token = null;
		for (int i = 0; i < tokens; i++) {
			token = stok.nextToken();
			switch (i) {
				case 0 :
					break;
				
				case 1 :
					break;
				
				case 2 :
					try {
						Integer.parseInt(token);
					} catch (NumberFormatException nfe) {
						appendToken(dateBuffer, token);
					}
					break;
				
				case 3 : 
					try {
						Integer.parseInt(token);
					} catch (NumberFormatException nfe) {
						appendToken(dateBuffer, token);
					}
					break;
				
				default : 
					appendToken(dateBuffer, token);
			}
		}
		try{
			Date date = DATE_FORMATTER.parse(dateBuffer.toString());
			return date;
		}catch(ParseException e){
			FoundationCheckupPlugin.logError(e);
		}
		return null;
	}
	
	private void appendToken(StringBuffer buffer, String token) {
		if (buffer.length() > 0) {
			buffer.append(LogEntry.SPACE);
		}
		buffer.append(token);
	}

}