/*  Copyright (C) 2009 Mobile Sorcery AB

    This program is free software; you can redistribute it and/or modify it
    under the terms of the Eclipse Public License v1.0.

    This program is distributed in the hope that it will be useful, but WITHOUT
    ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
    FITNESS FOR A PARTICULAR PURPOSE. See the Eclipse Public License v1.0 for
    more details.

    You should have received a copy of the Eclipse Public License v1.0 along
    with this program. It is also available at http://www.eclipse.org/legal/epl-v10.html
 */
package com.mobilesorcery.sdk.core;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.ErrorParserManager;
import org.eclipse.cdt.core.IErrorParser;
import org.eclipse.cdt.core.IMarkerGenerator;
import org.eclipse.cdt.core.model.ICModelMarker;
import org.eclipse.cdt.core.resources.ACBuilder;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.BuildAction;
import org.eclipse.ui.ide.IDE;

import com.mobilesorcery.sdk.core.LineReader.ILineHandler;
import com.mobilesorcery.sdk.internal.BuildSession;
import com.mobilesorcery.sdk.internal.PipeTool;
import com.mobilesorcery.sdk.internal.builder.MoSyncBuilderVisitor;
import com.mobilesorcery.sdk.internal.builder.MoSyncResourceBuilderVisitor;
import com.mobilesorcery.sdk.internal.dependencies.DependencyManager;
import com.mobilesorcery.sdk.profiles.IProfile;

/**
 * The main builder. This builder extends ACBuilder for its implementation of
 * {@link IMarkerGenerator}.
 * 
 * @author Mattias Bybro
 * 
 */
public class MoSyncBuilder extends ACBuilder {

    public final static String ID = CoreMoSyncPlugin.PLUGIN_ID + ".builder";

    public static final String COMPATIBLE_ID = "com.mobilesorcery.sdk.builder.builder";

    public static final String CONSOLE_ID = "com.mobilesorcery.build.console";

    /**
     * The prefix used by property initializers.
     */
    public static final String BUILD_PREFS_PREFIX = "build.prefs:";

    public final static String ADDITIONAL_INCLUDE_PATHS = BUILD_PREFS_PREFIX + "additional.include.paths";

    public final static String IGNORE_DEFAULT_INCLUDE_PATHS = BUILD_PREFS_PREFIX + "ignore.default.include.paths";

    public final static String ADDITIONAL_LIBRARY_PATHS = BUILD_PREFS_PREFIX + "additional.library.paths";

    public final static String IGNORE_DEFAULT_LIBRARY_PATHS = BUILD_PREFS_PREFIX + "ignore.default.library.paths";

    public static final String DEFAULT_LIBRARIES = BUILD_PREFS_PREFIX + "default.libraries";

    public final static String ADDITIONAL_LIBRARIES = BUILD_PREFS_PREFIX + "additional.libraries";

    public final static String IGNORE_DEFAULT_LIBRARIES = BUILD_PREFS_PREFIX + "ignore.default.libraries";

    public static final String LIB_OUTPUT_PATH = BUILD_PREFS_PREFIX + "lib.output.path";

    public static final String APP_OUTPUT_PATH = BUILD_PREFS_PREFIX + "app.output.path";

    public static final String DEAD_CODE_ELIMINATION = BUILD_PREFS_PREFIX + "dead.code.elim";

    public static final String EXTRA_LINK_SWITCHES = BUILD_PREFS_PREFIX + "extra.link.sw";

    public static final String EXTRA_RES_SWITCHES = BUILD_PREFS_PREFIX + "extra.res.sw";

    public static final String PROJECT_TYPE = BUILD_PREFS_PREFIX + "project.type";

    public static final String PROJECT_TYPE_APPLICATION = "app";

    public static final String PROJECT_TYPE_LIBRARY = "lib";

    public static final String EXTRA_COMPILER_SWITCHES = BUILD_PREFS_PREFIX + "gcc.switches";

    public static final String GCC_WARNINGS = BUILD_PREFS_PREFIX + "gcc.warnings";

    public static final String MEMORY_PREFS_PREFIX = BUILD_PREFS_PREFIX + "memory.";

    public static final String MEMORY_HEAPSIZE_KB = MEMORY_PREFS_PREFIX + "heap";

    public static final String MEMORY_STACKSIZE_KB = MEMORY_PREFS_PREFIX + "stack";

    public static final String MEMORY_DATASIZE_KB = MEMORY_PREFS_PREFIX + "data";

    public static final String USE_DEBUG_RUNTIME_LIBS = BUILD_PREFS_PREFIX + "runtime.debug";
    
    public static final String PROJECT_VERSION = BUILD_PREFS_PREFIX + "app.version";
    
    public static final String APP_NAME = BUILD_PREFS_PREFIX + "app.name";

    private static final String APP_CODE = "app.code";
   
    private static final String CONSOLE_PREPARED = "console.prepared";
    
    public static final int GCC_WALL = 1 << 1;

    public static final int GCC_WEXTRA = 1 << 2;

    public static final int GCC_WERROR = 1 << 3;

    public final class GCCLineHandler implements ILineHandler {

        private ErrorParserManager epm;

        public GCCLineHandler(ErrorParserManager epm) {
            this.epm = epm;
        }

        private String aggregateLine = "";

        public void newLine(String line) {
            // We need to 'aggregate' lines;
            // whenever a line is indented,
            // it is actually an extension
            // of last line - it's a hack to reconcile
            // the CDT error parser and the 'real world'
            if (isLineIndented(line)) {
                aggregateLine += " " + line.trim();
            } else {
                reportLine(aggregateLine);
                aggregateLine = line.trim();
            }
        }

        private boolean isLineIndented(String line) {
            return line.startsWith(Util.fill(' ', 2));
        }

        public void reportLine(String line) {
            try {
                // Kind of backwards..., first we remove a \n, and
                // now
                // we add it, so the ErrorParserManager can remove
                // it again...
                if (epm != null) {
                    // Write directly to emp, ignore
                    // getoutputstream.
                    epm.write((line + '\n').getBytes());
                }
            } catch (IOException e) {
                // Ignore.
            }
        }

        public void stop(IOException e) {
            newLine("");
            /*
             * if (aggregateLine.length() > 0) { reportLine(aggregateLine); }
             */
        }

        public void reset() {
            stop(null);
            aggregateLine = "";
        }
    }

    public MoSyncBuilder() {
    }

    /**
     * Returns the output path for a project + build variant. Please note that
     * there is another method for returning final output paths
     * 
     * @param project
     * @param variant
     * @return
     * @throws IllegalStateException
     *             If the variant is a finalizer variant
     */
    private static IPath getNoFinalizerOutputPath(IProject project, IBuildVariant variant) {
        return getNoFinalizerOutputPath(project, getPropertyOwner(MoSyncProject.create(project), variant.getConfigurationId()));
    }

    private static IPath getNoFinalizerOutputPath(IProject project, IPropertyOwner buildProperties) {
        String outputPath = buildProperties.getProperty(APP_OUTPUT_PATH);
        if (outputPath == null) {
            throw new IllegalArgumentException("No output path specified");
        }

        return toAbsolute(project.getLocation().append("Output"), outputPath);
    }

    /**
     * Converts a project-relative path to an absolute path.
     * 
     * @param path
     * @return An absolute path
     */
    public static IPath toAbsolute(IPath root, String pathStr) {
        Path path = new Path(pathStr);
        return root.append(path);
    }

    public static IPath getFinalOutputPath(IProject project, IBuildVariant variant) {
        if (!variant.isFinalizerBuild()) {
            throw new IllegalStateException("Finalizer variant required allowed here");
        }
        
        IProfile targetProfile = variant.getProfile();
        String outputPath = getPropertyOwner(MoSyncProject.create(project), variant.getConfigurationId()).getProperty(APP_OUTPUT_PATH);
        if (outputPath == null) {
            throw new IllegalArgumentException("No output path specified");
        }

        return toAbsolute(project.getLocation().append("FinalOutput"), outputPath).append(targetProfile.getVendor().getName()).append(targetProfile.getName());
    }

    public static IPath getOutputPath(IProject project, IBuildVariant variant) {
        return variant.isFinalizerBuild() ? getFinalOutputPath(project, variant) : getNoFinalizerOutputPath(project, variant);
    }

    public static IPath getProgramOutputPath(IProject project, IBuildVariant variant) {
        return getOutputPath(project, variant).append("program");
    }

    public static IPath getProgramCombOutputPath(IProject project, IBuildVariant variant) {
        return getOutputPath(project, variant).append("program.comb");
    }

    public static IPath getResourceOutputPath(IProject project, IBuildVariant variant) {
        return getOutputPath(project, variant).append("resources");
    }

    public static IPath getPackageOutputPath(IProject project, IBuildVariant variant) {
        if (variant.isFinalizerBuild()) {
            return getFinalOutputPath(project, variant).append("package");
        } else {
            return getOutputPath(project, variant).append(getAbbreviatedPlatform(variant.getProfile()));
        }
    }

    public static String getAbbreviatedPlatform(IProfile targetProfile) {
        String platform = targetProfile.getPlatform();
        String abbrPlatform = platform.substring("profiles\\runtime\\".length() + 1, platform.length());
        return abbrPlatform;
    }

    protected IProject[] build(int kind, Map args, IProgressMonitor monitor) throws CoreException {
        IProject project = getProject();

        if (MoSyncProject.NULL_DEPENDENCY_STRATEGY == PropertyUtil.getInteger(MoSyncProject.create(project), MoSyncProject.DEPENDENCY_STRATEGY,
                MoSyncProject.GCC_DEPENDENCY_STRATEGY)) {
            // TODO: At this point, we only have a GCC dependency strategy and a
            // "always full build" strategy
            kind = FULL_BUILD;
        }

        // Default incremental build does link but does not ask for confirmation
        // if it's an autobuild
        IBuildVariant variant = getActiveVariant(MoSyncProject.create(project), false);
        IBuildSession session = createIncrementalBuildSession(project, kind);
        if (kind == FULL_BUILD) {
            fullBuild(project, session, variant, null, monitor);
        } else {
            incrementalBuild(project, session, variant, null, monitor);
        }

        Set<IProject> dependencies = CoreMoSyncPlugin.getDefault().getProjectDependencyManager(ResourcesPlugin.getWorkspace()).getDependenciesOf(project);
        dependencies.add(project);

        return dependencies.toArray(new IProject[dependencies.size()]);
    }

    private boolean hasErrorMarkers(IProject project) throws CoreException {
        return hasErrorMarkers(project, IResource.DEPTH_INFINITE);
    }

    private boolean hasErrorMarkers(IProject project, int depth) throws CoreException {
        return project.findMaxProblemSeverity(ICModelMarker.C_MODEL_PROBLEM_MARKER, true, depth) == IMarker.SEVERITY_ERROR;
    }

    public void clean(IProgressMonitor monitor) {
        forgetLastBuiltState();
        IProject project = getProject();
        MoSyncProject mosyncProject = MoSyncProject.create(project);
        IBuildVariant variant = getActiveVariant(mosyncProject, false);
        clean(project, variant, monitor);
    }

    public void clean(IProject project, IBuildVariant variant, IProgressMonitor monitor) {
        IPath output = getOutputPath(project, variant);
        File outputFile = output.toFile();

        IProcessConsole console = CoreMoSyncPlugin.getDefault().createConsole(CONSOLE_ID);
        prepareConsole(null, console);

        console.addMessage(createBuildMessage("Cleaning", MoSyncProject.create(project), variant));
        Util.deleteFiles(getPackageOutputPath(project, variant).toFile(), null, 512, monitor);
        Util.deleteFiles(getProgramOutputPath(project, variant).toFile(), null, 1, monitor);
        Util.deleteFiles(getProgramCombOutputPath(project, variant).toFile(), null, 1, monitor);
        Util.deleteFiles(getResourceOutputPath(project, variant).toFile(), null, 1, monitor);
        Util.deleteFiles(outputFile, Util.getExtensionFilter("s"), 512, monitor);
        
        IBuildState buildState = MoSyncProject.create(project).getBuildState(variant);
        buildState.clear();
        buildState.save();
        buildState.setValid(true);
    }

    /**
     * Perfoms a full build of a project
     * 
     * @param project
     * @param variant
     * @param doClean
     * @param doBuildResources
     * @param doPack
     * @param resourceFilter
     * @param doLink
     * @param saveDirtyEditors
     * @param monitor
     * @return
     * @throws CoreException
     * @throws {@link OperationCanceledException} If the operation was canceled
     */
    public IBuildResult fullBuild(IProject project, IBuildSession session, IBuildVariant variant, 
            IFilter<IResource> resourceFilter, IProgressMonitor monitor) throws CoreException,
            OperationCanceledException {
        try {
            // TODO: Allow for setting build config explicitly!
            monitor.beginTask(MessageFormat.format("Full build of {0}", project.getName()), 8);
            if (session.doClean()) {
                clean(project, variant, new SubProgressMonitor(monitor, 1));
            } else {
                monitor.worked(1);
            }

            if (!session.doClean() && session.doSaveDirtyEditors()) {
                if (!saveAllEditors(project)) {
                    throw new OperationCanceledException();
                }
            }

            return incrementalBuild(project, session, variant, resourceFilter, monitor);
        } finally {
            monitor.done();
        }
    }

    IBuildResult build(IProject project, IResourceDelta[] ignoreMe, IBuildSession session, IBuildVariant variant, 
            IFilter<IResource> resourceFilter, IProgressMonitor monitor) {
        return null;
    }
    
    /**
     * Returns the pipe tool mode that should be used for the given kind
     * of input. 
     * 
     * @param profile The profile we want to use pipe-tool for.
     * @param isLib Indicates whether we are building a library or not.
     * @return The appropriate mode for the given profile.
     */
    String getPipeToolMode(IProfile profile, boolean isLib)
    {
        if ( isLib ) 
        {
        	return PipeTool.BUILD_LIB_MODE;
        }
        
        Map<String, Object> properties = profile.getProperties( );
        if ( properties.containsKey( "MA_PROF_OUTPUT_CPP" ) )
        {
        	return PipeTool.BUILD_GEN_CPP_MODE ;
        }
        else if ( properties.containsKey( "MA_PROF_OUTPUT_JAVA" ) )
        {
        	return PipeTool.BUILD_GEN_JAVA_MODE;
        }
        else
        {
        	return PipeTool.BUILD_C_MODE;
        }
    }
    
    /**
     * Returns true if any of the libraries that the given project depends
     * on have changed.
     * 
     * @param mosyncProject Project to check for changes. 
     * @param buildProperties Build properties of project.
     * @param programComb Latest built program file.
     * @return true if any of the libraries have changed, false otherwise. 
     */
    private boolean haveLibrariesChanged(MoSyncProject mosyncProject, IPropertyOwner buildProperties, IPath programComb)
    {
        long librariesTouched = mosyncProject.getLibraryLookup(buildProperties).getLastTouched();
        long programCombTouched = programComb.toFile().lastModified();
        return librariesTouched > programCombTouched;
    }
    
    IBuildResult incrementalBuild(IProject project, IBuildSession session, IBuildVariant variant, 
            IFilter<IResource> resourceFilter, IProgressMonitor monitor) throws CoreException 
    {
        if (CoreMoSyncPlugin.getDefault().isDebugging()) {
            CoreMoSyncPlugin.trace("Building project {0}", project);
        }
        
        BuildResult buildResult = new BuildResult(project);
        buildResult.setVariant(variant);
        Calendar timestamp = Calendar.getInstance();
        buildResult.setTimestamp(timestamp.getTimeInMillis());
        
        MoSyncProject mosyncProject = MoSyncProject.create(project);
        IBuildState buildState = mosyncProject.getBuildState(variant);
        
        ensureOutputIsMarkedDerived(project, variant);

        ErrorParserManager epm = createErrorParserManager(project);
        
        try {
        	/* Set up build monitor */
            monitor.beginTask(MessageFormat.format("Building {0}", project), 4);
            monitor.setTaskName("Clearing old problem markers");
            
            if (session.doLink() && !session.doBuildResources()) {
                throw new CoreException(new Status(IStatus.ERROR, CoreMoSyncPlugin.PLUGIN_ID,
                        "If resource building is suppressed, then linking should also be."));
            }
            
            // And we only remove things that are on the project.
            IFileTreeDiff diff = createDiff(buildState, session);
            boolean hadSevereBuildErrors = hasErrorMarkers(project, IResource.DEPTH_ZERO);
            if (hadSevereBuildErrors) {
                // Build all files
                diff = null;
            }

            if (diff == null || !buildState.isValid()) {
                buildState.getDependencyManager().clear();
            }

            IProcessConsole console = createConsole(session);

            DateFormat dateFormater = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.LONG);
            console.addMessage("Build started at " + dateFormater.format(timestamp.getTime()));
            console.addMessage(createBuildMessage("Building", mosyncProject, variant));

            /**
             * Compile source files.
             */
            
            /* Set up the compiler */
            MoSyncBuilderVisitor compilerVisitor = new MoSyncBuilderVisitor();
            compilerVisitor.setProject(project);           
            compilerVisitor.setVariant(variant);

            GCCLineHandler linehandler = new GCCLineHandler(epm);
            
            /* Set up pipe-tool */
            PipeTool pipeTool = new PipeTool();
            pipeTool.setAppCode(getCurrentAppCode(session));
            pipeTool.setProject(project);
            pipeTool.setConsole(console);
            pipeTool.setLineHandler(linehandler);
            IPropertyOwner buildProperties = getPropertyOwner(mosyncProject, variant.getConfigurationId());
            pipeTool.setArguments(buildProperties);

            /* Set up resource visitor */
            MoSyncResourceBuilderVisitor resourceVisitor = new MoSyncResourceBuilderVisitor();
            resourceVisitor.setProject(project);
            resourceVisitor.setVariant(variant);
            resourceVisitor.setPipeTool(pipeTool);
            IPath resource = getResourceOutputPath(project, variant);
            resourceVisitor.setOutputFile(resource);
            resourceVisitor.setDependencyProvider(compilerVisitor.getDependencyProvider());
            resourceVisitor.setDiff(diff);
            resourceVisitor.setResourceFilter(resourceFilter);

            if (session.doBuildResources()) {
                // First we build the resources...
                monitor.setTaskName("Assembling resources");
                resourceVisitor.incrementalCompile(monitor, buildState.getDependencyManager());
            }

            // ...and then the actual code is compiled
            IPath compileDir = getOutputPath(project, variant);
            IProfile targetProfile = variant.getProfile();
            monitor.setTaskName(MessageFormat.format("Compiling for {0}", targetProfile));

            /* Compile all files */
            compilerVisitor.setConsole(console);
            compilerVisitor.setExtraCompilerSwitches(buildProperties.getProperty(EXTRA_COMPILER_SWITCHES));
            Integer gccWarnings = PropertyUtil.getInteger(buildProperties, GCC_WARNINGS);
            compilerVisitor.setGCCWarnings(gccWarnings == null ? 0 : gccWarnings.intValue());
            compilerVisitor.setOutputPath(compileDir);
            compilerVisitor.setLineHandler(linehandler);
            compilerVisitor.setBuildResult(buildResult);
            compilerVisitor.setDiff(diff);
            compilerVisitor.setResourceFilter(resourceFilter);
            compilerVisitor.incrementalCompile(monitor, buildState.getDependencyManager());

            /* Update dependencies */
            IResource[] allAffectedResources = compilerVisitor.getAllAffectedResources();
            Set<IProject> projectDependencies = computeProjectDependencies(monitor, mosyncProject, buildState, allAffectedResources);
            DependencyManager<IProject> projectDependencyMgr = CoreMoSyncPlugin.getDefault().getProjectDependencyManager(ResourcesPlugin.getWorkspace());
            projectDependencyMgr.setDependencies(project, projectDependencies);
            
            monitor.worked(1);
            if (monitor.isCanceled()) {
                return buildResult;
            }
            monitor.setTaskName(MessageFormat.format("Packaging for {0}", targetProfile));

            IPath program = getProgramOutputPath(project, variant);
            IPath programComb = getProgramCombOutputPath(project, variant);

            // We'll relink if we had some non-empty delta; we'll use
            // the compiler visitor to tell us that. We'll also relink
            // if some library that our project depends on is changed.
            
            boolean librariesHaveChanged = haveLibrariesChanged(mosyncProject, buildProperties, programComb);
            boolean requiresLinking = session.doLink() && (allAffectedResources.length > 0 || librariesHaveChanged);
            if (librariesHaveChanged) {
            	console.addMessage("Libraries have changed, will require re-linking");
            }

            /**
             * Perform linking.
             */
            boolean isLib = PROJECT_TYPE_LIBRARY.equals(mosyncProject.getProperty(PROJECT_TYPE));
            int errorCount = compilerVisitor.getErrorCount();
            if (errorCount == 0 && requiresLinking) {
                String[] objectFiles = compilerVisitor.getObjectFilesForProject(project);
                pipeTool.setInputFiles(objectFiles);
                String pipeToolMode = getPipeToolMode( targetProfile, isLib );
                pipeTool.setMode( pipeToolMode );
                IPath libraryOutput = computeLibraryOutput(mosyncProject, buildProperties);
                pipeTool.setOutputFile(isLib ? libraryOutput : program);
                pipeTool.setLibraryPaths(getLibraryPaths(project, buildProperties));
                pipeTool.setLibraries(getLibraries(buildProperties));
                boolean elim = !isLib && PropertyUtil.getBoolean(buildProperties, DEAD_CODE_ELIMINATION);
                pipeTool.setDeadCodeElimination(elim);
                pipeTool.setCollectStabs(true);

                String[] extraLinkerSwitches = PropertyUtil.getStrings(buildProperties, EXTRA_LINK_SWITCHES);
                pipeTool.setExtraSwitches(extraLinkerSwitches);

                if (objectFiles.length > 0) {
                    pipeTool.run();
                    
                    // If needed, run a second time to generate IL
                    if (isLib == false && pipeToolMode.equals(PipeTool.BUILD_C_MODE) == false) {
                    	pipeTool.setMode(PipeTool.BUILD_C_MODE);
                		pipeTool.run();
                    }
                }

                if (elim) {
                    PipeTool elimPipeTool = new PipeTool();
                    elimPipeTool.setProject(project);
                    elimPipeTool.setLineHandler(linehandler);
                    elimPipeTool.setNoVerify(true);
                    elimPipeTool.setGenerateSLD(false);
                    elimPipeTool.setMode(PipeTool.BUILD_C_MODE);
                    elimPipeTool.setOutputFile(program);
                    elimPipeTool.setConsole(console);
                    elimPipeTool.setExtraSwitches(extraLinkerSwitches);
                    elimPipeTool.setAppCode(getCurrentAppCode(session));
                    elimPipeTool.setArguments(buildProperties);
                    File rebuildFile = new File(elimPipeTool.getExecDir(), "rebuild.s");
                    elimPipeTool.setInputFiles(new String[] { rebuildFile.getAbsolutePath() });
                    elimPipeTool.run();
                }

                if (!isLib) {
                    // Create "comb" file - program + resources in one. We'll
                    // always
                    // make one, even though no resources present.
                    ArrayList<File> parts = new ArrayList<File>();
                    parts.add(program.toFile());
                    if (resourceVisitor.getResourceFiles().length > 0 && program.toFile().exists() && resource.toFile().exists()) {
                        parts.add(resource.toFile());
                    }

                    if (parts.size() > 1) {
                        console.addMessage(MessageFormat.format("Combining {0} into one large file, {1}", Util.join(parts.toArray(), ", "), programComb
                                .toFile()));
                    }
                    Util.mergeFiles(new SubProgressMonitor(monitor, 1), parts.toArray(new File[parts.size()]), programComb.toFile());
                }
            }

            /**
             * Package application.
             */
            boolean shouldPack = session.doPack() && !isLib && errorCount == 0;
            if (shouldPack) {
                IPackager packager = targetProfile.getPackager();
                packager.setParameter(USE_DEBUG_RUNTIME_LIBS, Boolean.toString(PropertyUtil
                        .getBoolean(buildProperties, USE_DEBUG_RUNTIME_LIBS)));
                packager.createPackage(mosyncProject, variant, buildResult);

                if (buildResult.getBuildResult() == null || !buildResult.getBuildResult().exists()) {
                    throw new IOException(MessageFormat.format("Failed to create package for {0} (platform: {1})", targetProfile, getAbbreviatedPlatform(targetProfile)));
                } else {
                    console.addMessage(MessageFormat.format("Created package: {0} (platform: {1})", buildResult.getBuildResult(), getAbbreviatedPlatform(targetProfile)));
                }
            }

            monitor.worked(1);

            console.addMessage("Build finished at " + dateFormater.format(Calendar.getInstance().getTime()));

            project.refreshLocal(IProject.DEPTH_INFINITE, new SubProgressMonitor(monitor, 1));

            buildResult.setSuccess(true);
            
            return buildResult;
        } catch (OperationCanceledException e) { 
            // That's ok, why?
            return buildResult; 
        } catch (Exception e) {
            e.printStackTrace();
            throw new CoreException(new Status(IStatus.ERROR, CoreMoSyncPlugin.PLUGIN_ID, e.getMessage(), e));
        } finally {
            if (!variant.isFinalizerBuild()) {
                epm.reportProblems();
            }
            if (!monitor.isCanceled() && !buildResult.success() && !hasErrorMarkers(project)) {
                addBuildFailedMarker(project);
            } else if (buildResult.success()) {
                clearCMarkers(project);
            }
            
            saveBuildState(buildState, mosyncProject, buildResult);
        }
    }

    private String getCurrentAppCode(IBuildSession session) {
        // TODO: This will result in *most* equivalent apps to share
        // app code across devices; in particular finalization will always
        // share app code.
        String appCode = session.getProperties().get(APP_CODE);
        if (Util.isEmpty(appCode)) {
            appCode = PipeTool.generateAppCode();
            session.getProperties().put(APP_CODE, appCode);
        }
        
        return appCode;
    }

    private String createBuildMessage(String buildType, MoSyncProject project, IBuildVariant variant) {
        StringBuffer result = new StringBuffer();
        
        if (variant.isFinalizerBuild()) {
            result.append("*** FINALIZER BUILD ***\n");
        }
        
        result.append(MessageFormat.format("{0}: Project {1} for profile {2}", buildType, project.getName(), variant.getProfile()));
        if (project.areBuildConfigurationsSupported() && variant.getConfigurationId() != null) {
            result.append(MessageFormat.format("\nConfiguration: {0}", variant.getConfigurationId()));
        }
        
        return result.toString();
    }

    // returns null if a full build should be performed.
    private IFileTreeDiff createDiff(IBuildState buildState, IBuildSession session) throws CoreException {
        Set<String> changedProperties = buildState.getChangedBuildProperties();
        
        if (session.doClean() || buildState.fullRebuildNeeded()) {
            // Always full build after clean.
            return null;
        }
        
        // Also, if the variant we want to build does not match what was previously built here,
        // then we'll do a full rebuild.
        IBuildResult previousResult = buildState.getBuildResult();
        if (previousResult == null || !buildState.getBuildVariant().equals(previousResult.getVariant())) {
            return null;
        }
        
        if (changedProperties.isEmpty()) {
            return buildState.createDiff();    
        } else {
            if (CoreMoSyncPlugin.getDefault().isDebugging()) {
                CoreMoSyncPlugin.trace("Changed build properties, full rebuild required. Changed properties: {0}", changedProperties);
            }
            return null;
        }
        
    }

    private void ensureOutputIsMarkedDerived(IProject project, IBuildVariant variant) throws CoreException {
        ensureFolderIsMarkedDerived(project.getFolder("FinalOutput"));
        ensureFolderIsMarkedDerived(project.getFolder("Output"));
        
        IPath outputPath = getOutputPath(project, variant);
        IContainer[] outputFolder = project.getWorkspace().getRoot().findContainersForLocation(outputPath);
        for (int i = 0; i < outputFolder.length; i++) {
            ensureFolderIsMarkedDerived((IFolder) outputFolder[i]);
        }
    }
    
    private void ensureFolderExists(IFolder folder) throws CoreException {
        if (!folder.exists()) {
            if (!folder.getParent().exists() && folder.getParent().getType() == IResource.FOLDER) {
                ensureFolderExists((IFolder) folder.getParent());
            }
            folder.create(true, true, new NullProgressMonitor());
        }        
    }
    
    private void ensureFolderIsMarkedDerived(IFolder folder) throws CoreException {
        ensureFolderExists(folder);
        
        if (!folder.isDerived()) {
            folder.setDerived(true);
        }
    }

    private void saveBuildState(IBuildState buildState, MoSyncProject project, BuildResult buildResult) throws CoreException {
        buildState.updateResult(buildResult);
        buildState.updateState(project.getWrappedProject());
        buildState.updateBuildProperties(project.getProperties());
        buildState.fullRebuildNeeded(buildResult == null || !buildResult.success());
        buildState.save();
        buildState.setValid(true);
    }

    private void addBuildFailedMarker(IProject project) throws CoreException {
        // Ensure there is a build failed marker if the build failed; will cause
        // all failed builds to
        // be completely rebuilt later.
        IMarker marker = project.createMarker(ICModelMarker.C_MODEL_PROBLEM_MARKER);
        marker.setAttribute(IMarker.MESSAGE, "Last build failed");
        marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_ERROR);
    }

    public static IPropertyOwner getPropertyOwner(MoSyncProject mosyncProject, String configId) {
        return getPropertyOwner(mosyncProject, mosyncProject.getBuildConfiguration(configId));
    }

    static IPropertyOwner getPropertyOwner(MoSyncProject mosyncProject, IBuildConfiguration config) {
        return mosyncProject.areBuildConfigurationsSupported() && config != null ? config.getProperties() : mosyncProject;
    }

    /**
     * 'Prepares' a console for printing -- first checks whether the CDT
     * preference is set to clear the console, and if so, clears it.
     * 
     * @param console
     */
    private void prepareConsole(IBuildSession session, IProcessConsole console) {
        boolean needsPreparing = !CoreMoSyncPlugin.isHeadless();
        needsPreparing &= (session != null && session.getProperties().get(CONSOLE_PREPARED) == null);

        if (needsPreparing) {
            console.prepare();
            if (session != null) {
                session.getProperties().put(CONSOLE_PREPARED, Boolean.TRUE.toString());
            }
        }
    }
    
    /**
     * Creates a console that is ready for printing.
     * 
     * @param session The current build session.
     * @return A console that is ready for printing.
     */
    private IProcessConsole createConsole(IBuildSession session)
    {
        IProcessConsole console = CoreMoSyncPlugin.getDefault().createConsole(CONSOLE_ID);
        prepareConsole(session, console);

        if (!MoSyncTool.getDefault().isValid()) {
            String error = MoSyncTool.getDefault().validate();
            console.addMessage(MessageFormat.format("MoSync Tool not properly initialized: {0}", error));
            console.addMessage("- go to Window > Preferences > MoSync Tool to set the MoSync home directory");
        }
        
        return console;
    }

    private Set<IProject> computeProjectDependencies(IProgressMonitor monitor, MoSyncProject mosyncProject, IBuildState buildState, IResource[] allAffectedResources) {
        IProject project = mosyncProject.getWrappedProject();
        monitor.setTaskName(MessageFormat.format("Computing project dependencies for {0}", project.getName()));
        DependencyManager<IProject> projectDependencies = CoreMoSyncPlugin.getDefault().getProjectDependencyManager(ResourcesPlugin.getWorkspace());
        projectDependencies.clearDependencies(project);
        HashSet<IProject> allProjectDependencies = new HashSet<IProject>();
        Set<IResource> dependencies = buildState.getDependencyManager().getDependenciesOf(Arrays.asList(allAffectedResources));
        for (IResource resourceDependency : dependencies) {
            if (resourceDependency.getType() != IResource.ROOT) {
                allProjectDependencies.add(resourceDependency.getProject());
            }
        }

        // No deps on self
        allProjectDependencies.remove(project);

        if (CoreMoSyncPlugin.getDefault().isDebugging()) {
            CoreMoSyncPlugin
                    .trace(MessageFormat.format("Computed project dependencies. Project {0} depends on {1}", project.getName(), allProjectDependencies));
        }
        return allProjectDependencies;
    }

    public static IPath computeLibraryOutput(MoSyncProject mosyncProject, IPropertyOwner buildProperties) {
        String outputPath = buildProperties.getProperty(LIB_OUTPUT_PATH);
        if (outputPath == null) {
            throw new IllegalArgumentException("Library path is not specified");
        }

        return toAbsolute(mosyncProject.getWrappedProject().getLocation().append("Output"), outputPath);
    }

    /**
     * Clears all C markers of the given resource
     * 
     * @param resource
     * @param severityError
     * @return true if at least one marker was cleared, false otherwise
     * @throws CoreException
     * @throws CoreException
     */
    public static boolean clearCMarkers(IResource resource) throws CoreException {
        return clearCMarkers(resource, IMarker.SEVERITY_INFO);
    }

    public static boolean clearCMarkers(IResource resource, int severity) throws CoreException {
        if (!resource.exists()) {
            return false;
        }

        IMarker[] markers = resource.findMarkers(ICModelMarker.C_MODEL_PROBLEM_MARKER, true, IResource.DEPTH_INFINITE);
        IMarker[] toBeRemoved = markers;

        if (severity != IMarker.SEVERITY_INFO) {
            ArrayList<IMarker> toBeRemovedList = new ArrayList<IMarker>();
            for (int i = 0; i < markers.length; i++) {
                Object severityOfMarker = markers[i].getAttribute(IMarker.SEVERITY);
                if (severityOfMarker instanceof Integer) {
                    if (((Integer) severityOfMarker) >= severity) {
                        toBeRemovedList.add(markers[i]);
                    }
                }
            }
            toBeRemoved = toBeRemovedList.toArray(new IMarker[0]);
        }

        resource.getWorkspace().deleteMarkers(toBeRemoved);
        return toBeRemoved.length > 0;
    }

    private ErrorParserManager createErrorParserManager(IProject project) {
        String epId = CCorePlugin.PLUGIN_ID + ".GCCErrorParser";
        IErrorParser[] gccEp = CCorePlugin.getDefault().getErrorParser(epId);
        if (gccEp.length < 1) {
            return null; // No error parser for gcc available.
        } else {
            return new ErrorParserManager(project, this, new String[] { epId });
        }
    }

    public static IPath[] getBaseIncludePaths(MoSyncProject project, IBuildVariant variant) {
        IPropertyOwner buildProperties = getPropertyOwner(project, variant.getConfigurationId());
        
        ArrayList<IPath> result = new ArrayList<IPath>();
        if (!PropertyUtil.getBoolean(buildProperties, IGNORE_DEFAULT_INCLUDE_PATHS)) {
            result.addAll(Arrays.asList(MoSyncTool.getDefault().getMoSyncDefaultIncludes()));
        }
        
        result.addAll(Arrays.asList(MoSyncBuilder.getProfileIncludes(variant.getProfile())));

        IPath[] additionalIncludePaths = PropertyUtil.getPaths(buildProperties, ADDITIONAL_INCLUDE_PATHS);
        for (int i = 0; i < additionalIncludePaths.length; i++) {
            if (additionalIncludePaths[i].getDevice() == null) {
                // Then might be project relative path.
                IPath relativeAdditionalIncludePath = project.getWrappedProject().getLocation().append(additionalIncludePaths[i]);
                if (relativeAdditionalIncludePath.toFile().exists()) {
                    additionalIncludePaths[i] = relativeAdditionalIncludePath;
                }
            }
        }

        if (additionalIncludePaths != null) {
            result.addAll(Arrays.asList(additionalIncludePaths));
        }

        return result.toArray(new IPath[0]);
    }

    public static IPath[] getProfileIncludes(IProfile profile) {
        IPath profilePath = MoSyncTool.getDefault().getProfilePath(profile);
        return profilePath == null ? new IPath[0] : new IPath[] { profilePath };
    }

    public static IPath[] getLibraryPaths(IProject project, IPropertyOwner buildProperties) {
        ArrayList<IPath> result = new ArrayList<IPath>();
        if (!PropertyUtil.getBoolean(buildProperties, IGNORE_DEFAULT_LIBRARY_PATHS)) {
            result.addAll(Arrays.asList(MoSyncTool.getDefault().getMoSyncDefaultLibraryPaths()));
        }

        IPath[] additionalLibraryPaths = PropertyUtil.getPaths(buildProperties, ADDITIONAL_LIBRARY_PATHS);

        if (additionalLibraryPaths != null) {
            result.addAll(Arrays.asList(additionalLibraryPaths));
        }

        return result.toArray(new IPath[0]);
    }

    static IPath[] getLibraries(IPropertyOwner buildProperties) {
        // Ehm, I think I've seen this code elsewhere...
        ArrayList<IPath> result = new ArrayList<IPath>();
        if (!PropertyUtil.getBoolean(buildProperties, IGNORE_DEFAULT_LIBRARIES)) {
            result.addAll(Arrays.asList(PropertyUtil.getPaths(buildProperties, DEFAULT_LIBRARIES)));
        }

        IPath[] additionalLibraries = PropertyUtil.getPaths(buildProperties, ADDITIONAL_LIBRARIES);

        if (additionalLibraries != null) {
            result.addAll(Arrays.asList(additionalLibraries));
        }

        return result.toArray(new IPath[0]);
    }

    public static boolean isBuilderPreference(String preferenceKey) {
        return preferenceKey != null && preferenceKey.startsWith(BUILD_PREFS_PREFIX);
    }

    public static IProject getProject(ILaunchConfiguration launchConfig) throws CoreException {
        String projectName = launchConfig.getAttribute(ILaunchConstants.PROJECT, "");
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists()) {
            throw new CoreException(new Status(IStatus.ERROR, CoreMoSyncPlugin.PLUGIN_ID, MessageFormat.format("Cannot launch: Project {0} does not exist",
                    project.getName())));
        }

        return project;
    }

    public static boolean saveAllEditors(IResource resource) {
        ArrayList<IResource> resourceList = new ArrayList<IResource>();
        resourceList.add(resource);
        return saveAllEditors(resourceList);
    }

    public static boolean saveAllEditors(final List<IResource> resources) {
        if (CoreMoSyncPlugin.isHeadless()) {
            return true;
        }

        final boolean confirmSave = !BuildAction.isSaveAllSet();
        final boolean[] result = new boolean[1];
        result[0] = true;

        Display display = PlatformUI.getWorkbench().getDisplay();
        if (display != null) {
            display.syncExec(new Runnable() {
                public void run() {
                    if (!IDE.saveAllEditors(resources.toArray(new IResource[0]), confirmSave)) {
                        result[0] = false;
                    }
                }
            });
        }

        return result[0];
    }

    /**
     * Returns a build variant that represents the currently active project
     * settings.
     * 
     * @param isFinalizerBuild
     * @return
     */
    public static IBuildVariant getActiveVariant(MoSyncProject project, boolean isFinalizerBuild) {
        IBuildConfiguration cfg = project.getActiveBuildConfiguration();
        String cfgId = project.areBuildConfigurationsSupported() && cfg != null ? cfg.getId() : null;

        return new BuildVariant(project.getTargetProfile(), cfgId, isFinalizerBuild);
    }

    public static IBuildVariant getFinalizerVariant(MoSyncProject project, IProfile profile) {
        // TODO: Per-profile finalizer cfg, now we just rely on the finalizer
        // parser to
        // change config before finalizing.
        IBuildConfiguration cfg = project.getActiveBuildConfiguration();
        String cfgId = project.areBuildConfigurationsSupported() && cfg != null ? cfg.getId() : null;
        return new BuildVariant(profile, cfgId, true);
    }
    
    public static IBuildSession createFinalizerBuildSession(List<IBuildVariant> variants) {
        return new BuildSession(variants, BuildSession.DO_LINK | BuildSession.DO_PACK | BuildSession.DO_BUILD_RESOURCES | BuildSession.DO_CLEAN);
    }
    
    public static IBuildSession createCompileOnlySession(IBuildVariant variant) {
        return new BuildSession(Arrays.asList(variant), 0);
    }

    /**
     * Creates a build session with all build steps except CLEAN
     * @param variant
     * @return
     */
    public static IBuildSession createDefaultBuildSession(IBuildVariant variant) {
        return new BuildSession(Arrays.asList(variant), BuildSession.ALL - BuildSession.DO_CLEAN);
    }

    public static IBuildSession createCleanBuildSession(IBuildVariant variant) {
        return new BuildSession(Arrays.asList(variant), BuildSession.ALL);
    }
    
    /**
     * Returns a build session for incremental builds (ie the kind of build session
     * created upon calls to the <code>build</code> method).
     * @param kind The build kind, for example <code>IncrementalProjectBuilder.FULL_BUILD</code>.
     * @return
     */
    public static IBuildSession createIncrementalBuildSession(IProject project, int kind) {
        boolean doPack = kind == FULL_BUILD;
        boolean doClean = kind == FULL_BUILD;
        IBuildVariant variant = getActiveVariant(MoSyncProject.create(project), false);
        return new BuildSession(Arrays.asList(variant), BuildSession.DO_BUILD_RESOURCES | BuildSession.DO_LINK | (doPack ? BuildSession.DO_PACK : 0) | (doClean ? BuildSession.DO_CLEAN : 0));
    }

    public static IPath getMetaDataPath(MoSyncProject project, IBuildVariant variant) {
        return getOutputPath(project.getWrappedProject(), variant).append(".metadata");
    }
}
