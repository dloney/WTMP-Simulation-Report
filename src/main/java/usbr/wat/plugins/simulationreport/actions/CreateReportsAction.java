/*
 * Copyright 2021  Hydrologic Engineering Center (HEC).
 * United States Army Corps of Engineers
 * All Rights Reserved.  HEC PROPRIETARY/CONFIDENTIAL.
 * Source may not be released without written approval
 * from HEC
 */
package usbr.wat.plugins.simulationreport.actions;

import java.awt.EventQueue;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

import org.python.core.PyCode;
import org.python.util.PythonInterpreter;

import com.rma.io.FileManagerImpl;
import com.rma.io.RmaFile;
import com.rma.model.Project;

import hec2.plugin.model.ModelAlternative;
import hec2.wat.model.WatSimulation;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRExporter;
import net.sf.jasperreports.engine.JRPropertiesUtil;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.SimpleJasperReportsContext;
import rma.util.RMAIO;
import usbr.wat.plugins.actionpanel.ActionPanelPlugin;
import usbr.wat.plugins.actionpanel.actions.AbstractReportAction;
import usbr.wat.plugins.actionpanel.io.ReportOptions;
import usbr.wat.plugins.actionpanel.io.ReportXmlFile;
import usbr.wat.plugins.actionpanel.model.ReportsManager;
import usbr.wat.plugins.actionpanel.model.SimulationReportInfo;
import usbr.wat.plugins.actionpanel.ui.UsbrPanel;

/**
 * @author Mark Ackerman
 *
 */
// NOTE: Several helper members used in this class (e.g. REPORT_DIR (used as
// a Jasper report sub-folder here even though it's also reused as the XML
// output sub-folder), PYTHON_REPORT_BAT, JASPER_FILE, getObsDataPath(),
// findFpartForPython(), getDirectoryToUse(), runProcess(),
// getJasperRelativeFolder(), compileJasperFiles(), fillReport(), and the
// single-argument overload of runPythonScript(String)) are not declared in
// this file. They are inherited from the {@link AbstractReportAction}
// superclass (or one of its ancestors); comments below describe how they
// are used from this class's point of view.
//
// This class is the simulation-report counterpart to the forecast report's
// CreateReportsAction (see the sibling WTMP-Forecast-Report project) and
// orchestrates the same overall pipeline, but for a single, non-forecast
// simulation and without any ensemble set handling:
// 1. Write out an XML "data adapter" file describing the simulation
//    (createSimulationXmlFile).
// 2. Update/point the Jasper data adapter XML at that file (editDataAdapterFile,
//    inherited).
// 3. Run an external Python script (via a batch/command line call) that
//    performs the actual simulation data processing (runPythonScript).
// 4. Compile and fill the Jasper report template with the processed data and
//    export it to a PDF file (runJasperReport).
@SuppressWarnings("serial")
public class CreateReportsAction extends AbstractReportAction
{
	// Logger used throughout this class instead of System.out, unlike the
	// sibling forecast-report action which still uses System.out.println().
	private Logger _logger = Logger.getLogger(CreateReportsAction.class.getName());

	// Name of the folder (relative to the plugin's install directory) where
	// this report plugin's installed resources (scripts, templates, etc.) live.
	public static final String REPORT_INSTALL_FOLDER = "AutomatedReport";

	// Name of the folder (relative to the study directory) containing shared
	// observation data used to compare against simulation results.
	public static final String OBS_DATA_FOLDER   = "shared";
	// Name of the sub-folder (relative to the simulation folder or study
	// directory, depending on context) where generated reports/XML data live.
	public static final String REPORT_DIR = "reports";
	// Name of the sub-folder (relative to the study directory) where the
	// compiled/compiling Jasper report templates live.
	private static final String JASPER_REPORT_DIR = "Reports";
	// Prefix used for the generated PDF report file name; the current
	// timestamp and the file extension are appended to this when the report
	// is actually written out (see runJasperReport()).
	public static final String JASPER_OUT_FILE = "WTMP_report_draft-";
	// File extension applied to the generated report output file.
	public static final String REPORT_FILE_EXT = ".pdf";

	// Name of the folder (relative to the plugin's install/data directory)
	// that contains the python/batch scripts used to pre-process simulation
	// data before the Jasper report is generated.
	private static final String SCRIPTS_DIR = "scripts";
	// Maven artifact path (group/artifact style identifier) used to locate
	// this plugin's resources; also returned by getMavenPath().
	private static final String MAVEN_PATH = "usbr.wat.plugins/usbr-simulation-report";

	// Embedded Jython interpreter and compiled code, presumably used
	// elsewhere/by the superclass for running python-based report logic
	// in-process rather than via the external script call in
	// runPythonScript(); not directly referenced within this file.
	private PythonInterpreter  _interp;
	private PyCode _pycode;

	/**
	 * Creates the simulation "Create Reports" action, disabled by default,
	 * bound to the given parent panel.
	 *
	 * @param parentPanel the panel that owns/hosts this action
	 */
	public CreateReportsAction(UsbrPanel parentPanel)
	{
		super("Create Reports", parentPanel);

		// Disabled until enabled elsewhere once preconditions are met.
		setEnabled(false);
	}

	/**
	 * Runs the full simulation report creation pipeline for each of the
	 * given simulations in turn: writes the XML data file, updates the
	 * Jasper data adapter to point at it, runs the external Python
	 * pre-processing script, and finally fills/exports the Jasper report.
	 * <p>
	 * NOTE: although this method loops over every entry in {@code simInfos},
	 * a successful pipeline run for any single simulation returns
	 * immediately via {@code runJasperReport(simInfos.get(0), options)} —
	 * always reporting on the first simulation in the list regardless of
	 * which iteration triggered it — rather than continuing to process the
	 * remaining simulations.
	 *
	 * @param simInfos the simulation(s) to report on
	 * @param options  report output options (e.g. output format)
	 * @return {@code true} if the pipeline succeeded for some simulation and
	 *         the report was created; {@code false} if every simulation
	 *         failed to produce an XML file, or a later pipeline step failed
	 */
	public boolean createReport(List<SimulationReportInfo> simInfos, ReportOptions options)
	{
		WatSimulation sim;
		// Track elapsed time for the whole operation, logged in the finally block below.
		long t1 = System.currentTimeMillis();
		try
		{
			// Attempt the pipeline for each simulation in turn; the first
			// one whose XML file, data adapter update, and python script all
			// succeed causes an early return with the Jasper report result
			// (see the note in the javadoc above about which simulation is
			// actually reported on).
			for(int i = 0;i < simInfos.size(); i++ )
			{
				// Step 1: write the XML data file describing this simulation;
				// returns null if writing failed or was skipped.
				String xmlFile = createSimulationXmlFile(simInfos.get(i));
				if ( xmlFile != null )
				{
					// Step 2: point the Jasper data adapter at this
					// simulation's folder. Inherited from AbstractReportAction.
					if ( !editDataAdapterFile(simInfos.get(i).getSimFolder()))
					{
						return false;
					}
					// Step 3: run the external python pre-processing script
					// against the XML file just written.
					if ( runPythonScript(xmlFile))
					{
						// Step 4: compile/fill/export the Jasper report.
						return runJasperReport(simInfos.get(0), options);
					}
				}
			}
		}
		finally
		{
			// Always log the elapsed time for this operation, whether it
			// succeeded, failed, or fell through the loop without returning.
			long t2 = System.currentTimeMillis();
			_logger.info("Took "+(t2-t1)+" to create "+getName());
		}
		return false;
	}

	/**
	 * Builds the XML "data adapter" file that describes the given
	 * simulation. The file is written into the simulation's report
	 * directory, under a file name derived from the simulation's own name.
	 * <p>
	 * If the system property {@code SkipSimulationReportFile} is set to
	 * {@code true}, the XML file is not actually (re)written; the expected
	 * filename is simply returned as-is (useful for testing/debugging when
	 * an existing XML file should be reused without regenerating it).
	 *
	 * @param info the simulation to describe in the XML file
	 * @return the full path to the (written or pre-existing) XML file, or
	 *         {@code null} if writing the XML file failed
	 */
	private String createSimulationXmlFile(SimulationReportInfo info)
	{
		Project prj = Project.getCurrentProject();
		String studyDir = prj.getProjectDirectory();
		String simDir = info.getSimFolder();

		// Build the target XML file path: <simDir>/<REPORT_DIR>/<simName>.xml
		String filename = RMAIO.concatPath(simDir, REPORT_DIR);
		filename = RMAIO.concatPath(filename, RMAIO.userNameToFileName(info.getName())+".xml");

		// Testing/debugging escape hatch: skip regenerating the file and
		// just report back the filename that would have been used.
		if ( Boolean.getBoolean("SkipSimulationReportFile"))
		{
			return filename;
		}

		// Construct the base ReportXmlFile writer (unlike the forecast
		// report plugin, this class uses the base XML writer directly
		// rather than a forecast-specific subclass, since there is no
		// ensemble set information to add) and populate it with study and
		// simulation information before writing it out.
		ReportXmlFile xmlFile = new ReportXmlFile(filename);
		xmlFile.setStudyInfo(studyDir, getObsDataPath(studyDir));
		List<SimulationReportInfo>sims = new ArrayList<>();
		sims.add(info);
		xmlFile.setSimulationInfo(ActionPanelPlugin.getInstance().getActionsWindow().getSimulationGroup().getName(), sims);

		// Actually write the XML file to disk; only return the filename on success.
		if (  xmlFile.createXMLFile() )
		{
			return filename;
		}
		return null;
	}

	/**
	 * Runs the external python (via batch file) pre-processing script that
	 * prepares the simulation data used by the Jasper report.
	 * <p>
	 * The batch file is invoked with the following positional arguments (in
	 * order): the study/watershed directory, the simulation directory, the
	 * model program name (e.g. "ResSim"), the alternative's F-Part, the path
	 * to the observation data within the study, the alternative's name, and
	 * the base simulation name.
	 *
	 * @param sim               the simulation being processed
	 * @param modelAlt          the model alternative (e.g. the ResSim
	 *                          alternative) whose data should be processed
	 * @param baseSimulationName the base/root name of the simulation, used
	 *                          to help locate matching output
	 * @return {@code true} if the external process completed successfully;
	 *         {@code false} otherwise (including when no matching ResSim
	 *         alternative/F-Part could be found)
	 */
	public boolean runPythonScript(WatSimulation sim, ModelAlternative modelAlt, String baseSimulationName)
	{
		// first run the python script through the .bat file
		// bat file needs: 
		// 1. watershed path
		// 2. simulation path
		// 3. model name ... i.e. ResSim
		// 4. alternative's F-Part
		// 5. folder to the observation data in the study
		// 6. alternative's name
		// 7. simulation's base name

		// Track how long the external process takes, logged in the finally block below.
		long t1 = System.currentTimeMillis();
		try
		{
			// Locate the F-Part associated with the ResSim alternative for
			// this simulation/model alternative combination; required by the
			// python script to identify the correct DSS data to process.
			String fpart = findFpartForPython(sim, modelAlt);
			if ( fpart == null )
			{
				_logger.info("createReportAction:no ResSim Alternative found in Simulation "+sim);
				return false;
			}

			// Build up the command line (executable + arguments) to invoke
			// the batch file with the required positional parameters.
			List<String>cmdList = new ArrayList<>();
			String dirToUse = getDirectoryToUse();
			String exeFile = RMAIO.concatPath(dirToUse, PYTHON_REPORT_BAT);
			String studyDir = Project.getCurrentProject().getProjectDirectory();

			//cmdList.add("cmd.exe");
			//cmdList.add("/c");
			cmdList.add(exeFile);
			cmdList.add(studyDir);

			// hack for having a comma in the path and the RMAIO.userNameToFileName() not catching it
			String simDir = sim.getSimulationDirectory();
			simDir = RMAIO.removeChar(simDir, ',');
			cmdList.add(simDir);
			cmdList.add(modelAlt.getProgram());
			cmdList.add(fpart);

			// Path to the observation data used to compare against the simulation.
			String obsPath = getObsDataPath(studyDir);
			cmdList.add(obsPath);
			cmdList.add(modelAlt.getName());
			cmdList.add(baseSimulationName);

			// Launch the external batch/python process and wait for it to complete.
			return runProcess(cmdList, dirToUse);
		}
		finally
		{
			// Always log the elapsed time for this operation, whether it
			// succeeded, failed, or threw an exception.
			long t2 = System.currentTimeMillis();
			_logger.info("runProcess:time to run python for "+sim+" alt "+modelAlt+" is "+(t2-t1)+"ms");
		}
	}

	/**
	 * Compiles (if needed) and fills the Jasper report template for the
	 * given simulation, then exports the filled report to a timestamped PDF
	 * file within the simulation's report directory.
	 *
	 * @param info    the simulation report info describing the simulation
	 *                whose report is being created
	 * @param options the report output options (used to select/build the exporter)
	 * @return {@code true} if the report was compiled, filled and exported
	 *         successfully; {@code false} if any step failed
	 */
	public boolean runJasperReport(SimulationReportInfo info, ReportOptions options)
	{
		// Track total elapsed time for the whole Jasper report generation.
		long t1 = System.currentTimeMillis();
		try
		{
			//Log log = LogFactory.getLog(JasperFillManager.class);
			String studyDir = Project.getCurrentProject().getProjectDirectory();
			String simDir = info.getSimFolder();
			String jasperReportFolder = getJasperRelativeFolder();
			// Directory (within the study) where the compiled Jasper report
			// templates/resources are read from.
			String jasperReportDir = RMAIO.concatPath(studyDir, REPORT_DIR);
			String rptFile = RMAIO.concatPath(jasperReportDir, JASPER_FILE);
			String installDir = System.getProperty("user.dir");
			installDir = RMAIO.getDirectoryFromPath(installDir);
			//rptFile = RMAIO.concatPath(rptFile, JASPER_FILE);

			String inJasperFile = rptFile;

			//configure the context
			// NOTE: unlike the sibling forecast-report action, this context is
			// not given a FileRepositoryService/RepositoryService extension,
			// so relative resource references (e.g. sub-reports, images) in
			// the report template may not resolve the same way here.
			SimpleJasperReportsContext context = new SimpleJasperReportsContext();

			// Configure Jasper to use the Jaxen-based XPath executor, which
			// is required for XPath expressions used in the report's XML
			// data source queries.
			JRPropertiesUtil.getInstance(context).setProperty("net.sf.jasperreports.xpath.executer.factory",
					"net.sf.jasperreports.engine.util.xml.JaxenXPathExecuterFactory");

			// Ensure the simulation's report output folder exists before
			// attempting to write the generated PDF into it.
			String outputFile = RMAIO.concatPath(simDir, REPORT_DIR);
			RmaFile simDirFile = FileManagerImpl.getFileManager().getFile(outputFile);
			if ( !simDirFile.exists() )
			{
				if ( !simDirFile.mkdirs())
				{
					_logger.info("runJasperReport:failed to create folder "+simDirFile.getAbsolutePath());
				}
			}

			// Compile the Jasper report source files (.jrxml) into their
			// binary (.jasper) form if they have not already been compiled.
			if ( !compileJasperFiles(studyDir, installDir, jasperReportFolder))
			{
				return false;
			}

			// Fill the compiled report template with data (from the XML
			// data adapter produced earlier in the pipeline) and parameters.
			JasperPrint jasperPrint = fillReport(context, studyDir, installDir, jasperReportFolder, info, options);
			if ( jasperPrint == null )
			{
				return false;
			}

			// Build the final output file name: <REPORT_DIR>/<JASPER_OUT_FILE><timestamp><ext>
			outputFile = RMAIO.concatPath(outputFile, JASPER_OUT_FILE);
			SimpleDateFormat fmt= new SimpleDateFormat("yyyy.MM.dd-HHmm");

			Date date = new Date();
			outputFile = outputFile.concat(fmt.format(date));
			outputFile = outputFile.concat(REPORT_FILE_EXT);

			// fills compiled report with parameters and a connection
			JRExporter exporter = options.getOutputType().buildExporter(jasperPrint, outputFile);
			long t4 = System.currentTimeMillis();
			try
			{
				// Actually write the filled report out to the target file
				// (PDF or whichever format the exporter was built for).
				exporter.exportReport();
				_logger.info("runJasperReport:simulation report written to "+outputFile);
			}
			catch (JRException e)
			{
				// Export failed; log the stack trace and report failure to the caller.
				e.printStackTrace();
				return false;
			}

			long t5 = System.currentTimeMillis();
			_logger.info("runJasperReport:time to write jasper simulation report for "+info.getName()+ "is "+(t5-t4)+"ms");
			return true;
		}
		finally
		{
			// Always log the total elapsed time for this method, regardless
			// of whether it returned successfully or via an early return.
			long end = System.currentTimeMillis();
			_logger.info("runJasperReport:total time to create jasper simulation report for "+info.getName()+" is "+(end-t1)+"ms");
		}
	}

	/**
	 * @return the human-readable name of this report plugin, as shown in the UI
	 */
	@Override
	public String getName()
	{
		return "Simulation Report";
	}

	/**
	 * @return a short human-readable description of this report plugin
	 */
	@Override
	public String getDescription()
	{
		return "Report for a single Simulation";
	}

	/**
	 * @return {@code false}; this plugin does not produce comparison reports
	 */
	@Override
	public boolean isComparisonReport()
	{
		return false;
	}

	/**
	 * @return {@code false}; this plugin does not produce iteration reports
	 */
	@Override
	public boolean isIterationReport()
	{
		return false;
	}

	/**
	 * @return the plugin's display name, same as {@link #getName()}
	 */
	@Override
	public String toString()
	{
		return getName();
	}

	/**
	 * Registers a new instance of this action, with no parent panel bound,
	 * with the global {@link ReportsManager} so it becomes available as a
	 * report plugin elsewhere in the application.
	 */
	public static void register()
	{
		ReportsManager.register(new CreateReportsAction(null));
	}

	/**
	 * Simple entry point that schedules {@link #register()} to run on the
	 * Swing event dispatch thread. Primarily useful for manual/standalone
	 * testing of the registration logic.
	 *
	 * @param args unused command-line arguments
	 */
	public static void main(String[] args)
	{
		EventQueue.invokeLater(()->register());
	}

	/**
	 * @return the Maven artifact path used to locate this plugin's resources
	 */
	@Override
	public String getMavenPath()
	{
		return MAVEN_PATH;
	}

}
