/**
 * @author haitao.yao Jan 4, 2011
 */
package org.apache.maven.plugins;

import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;
import org.codehaus.plexus.util.cli.WriterStreamConsumer;

/**
 * @author haitao.yao Jan 4, 2011
 * @goal check
 */
public class JarConflictCheckMojo extends AbstractMojo {

	/**
	 * 
	 * @parameter expression="${project.build.directory}"
	 * @required
	 */
	private String targetDir;

	/**
	 * @parameter expression="${project.artifactId}"
	 * @required
	 */
	private String artifactId;

	private Map<String, JarEntryRecord> allJarEntryRecords = new TreeMap<String, JarEntryRecord>();

	private DuplicateClassContainer duplicateContainer = new DuplicateClassContainer();

	/**
	 * @parameter expression="${jarconflict.skip}"
	 */
	private boolean skip = false;

	/**
	 * @parameter expression="${basedir}"
	 * @required
	 * */
	protected File basedir;

	/**
	 * @parameter expression="${jarconflict.debug}"
	 */
	private boolean debug = false;

	/**
	 * @parameter expression="${jarconflict.details}"
	 */
	private boolean printDetails = false;

	/**
	 * @parameter expression="${project.build.finalName}"
	 * @required=true
	 */
	private String finalName;

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.maven.plugin.Mojo#execute()
	 */
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (skip) {
			getLog().info(artifactId + " jar conflict check skipped");
			return;
		}
		checkProjectType();
		File dependencyFolder = new File(new File(new File(this.targetDir),
				this.finalName), "WEB-INF/lib");
		if (!dependencyFolder.exists()) {
			throw new MojoExecutionException(dependencyFolder
					+ " not exits, execute mvn install first");
		}
		// File dependencyFolder = getDependencyOutputFolder();
		// copyDependencies(dependencyFolder);
		File[] jarFiles = getJarFiles(dependencyFolder);
		if (jarFiles == null || jarFiles.length == 0) {
			throw new MojoExecutionException(dependencyFolder
					+ " has no jar files");
		}
		try {
			checkJarFiles(jarFiles);
		} catch (IOException e) {
			String message = "Failed to check jar duplicate for "
					+ this.artifactId;
			getLog().error(message, e);
			throw new MojoExecutionException(message, e);
		}
		printResult();
	}

	/**
	 * @throws MojoExecutionException
	 * 
	 */
	private void checkProjectType() throws MojoExecutionException {
		Model model = this.readPom();
		String packagingType = model.getPackaging();
		if (!"war".equalsIgnoreCase(packagingType)) {
			throw new MojoExecutionException(
					"only can be used to check war project");
		}
	}

	public String getTestDependencyArtifactIds() throws MojoExecutionException {
		StringBuilder result = new StringBuilder();
		Model model = readPom();
		List<Dependency> dependencies = model.getDependencies();
		for (Dependency dep : dependencies) {
			if ("test".equalsIgnoreCase(dep.getScope())) {
				result.append(dep.getArtifactId()).append(",");
			}
		}
		return result.toString().isEmpty() ? "" : result.toString().substring(
				0, result.length() - 1);
	}

	public Model readPom() throws MojoExecutionException {
		File pom = new File(this.basedir, "pom.xml");
		Model model = null;
		MavenXpp3Reader pomReader = new MavenXpp3Reader();
		try {
			model = pomReader.read(new FileReader(pom));
		} catch (Exception e) {
			throw new MojoExecutionException("Error to read pom: "
					+ e.getMessage(), e);
		}
		if (model == null) {
			throw new MojoExecutionException("Failed to read pom: "
					+ pom.getAbsolutePath());
		}
		return model;
	}

	/**
	 * @param jarFiles
	 * @throws IOException
	 */
	private void checkJarFiles(File[] jarFiles) throws IOException {
		for (File file : jarFiles) {
			JarFile jarFile = new JarFile(file);
			String fileName = file.getName();
			Enumeration<JarEntry> entries = jarFile.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				String name = entry.toString();
				if (!name.endsWith(".class")) {
					continue;
				}
				JarEntryRecord record = new JarEntryRecord(fileName, name);
				JarEntryRecord duplicate = this.allJarEntryRecords.get(name);
				if (duplicate == null) {
					this.allJarEntryRecords.put(name, record);
				} else {
					duplicateContainer.add(record).add(duplicate);
					// getLog().error(
					// String.format(
					// "duplicate class found for %s, class name: %s, filename: %s, %s",
					// new Object[] { this.artifactId,
					// name.replace("/", "."), fileName,
					// duplicate.jarFileName }));
					// System.exit(1);

				}
			}
		}
	}

	/**
	 * 
	 */
	private void printResult() {
		Set<String> jarFileNames = new HashSet<String>();
		if (this.duplicateContainer.isEmpty()) {
			getLog().info("#################################################");
			getLog().info(
					"#################################################\n\n\n");
			getLog().info(this.artifactId + " has no class conflicts");
			getLog().info(
					"\n\n\n#################################################");
			getLog().info("#################################################");
		} else {
			getLog().info("==================================");
			StringBuilder result = new StringBuilder(
					"\nduplicate class file list: ");
			for (Map.Entry<String, Set<String>> e : this.duplicateContainer.duplicateEntries
					.entrySet()) {
				result.append(e.getKey().replace("/	", ".")).append("\n");
				for (String value : e.getValue()) {
					result.append("\t").append(value).append("\n");
					jarFileNames.add(value);
				}
				result.append("\n");
			}
			if (this.printDetails) {
				getLog().warn(result.toString());
			}
			StringBuilder jarFileResult = new StringBuilder(
					"\nJar files related: \n");
			for (String f : jarFileNames) {
				jarFileResult.append("\t").append(f).append("\n");
			}
			getLog().warn(jarFileResult.toString());
			getLog().info("==================================");
			System.exit(1);
		}
	}

	private static class DuplicateClassContainer {
		final private Map<String, Set<String>> duplicateEntries = new HashMap<String, Set<String>>();

		DuplicateClassContainer add(JarEntryRecord newRecord) {
			Set<String> entry = this.duplicateEntries.get(newRecord.entryName);
			if (entry == null) {
				entry = new HashSet<String>();
				this.duplicateEntries.put(newRecord.entryName, entry);
			}
			entry.add(newRecord.jarFileName);
			return this;
		}

		boolean isEmpty() {
			return this.duplicateEntries.isEmpty();
		}
	}

	/**
	 * @param dependencyFolder
	 * @return
	 */
	private File[] getJarFiles(File dependencyFolder) {
		File[] jarFiles = dependencyFolder.listFiles(new FilenameFilter() {

			public boolean accept(File dir, String name) {
				return name.trim().toLowerCase().endsWith("jar");
			}
		});
		if (jarFiles == null || jarFiles.length == 0) {
			getLog().info("no dependencies for " + this.artifactId);
			System.exit(0);
		}
		return jarFiles;
	}

	private static final class JarEntryRecord {
		final String jarFileName;

		final String entryName;

		/**
		 * @param jarFileName
		 * @param entryName
		 */
		private JarEntryRecord(String jarFileName, String entryName) {
			super();
			this.jarFileName = jarFileName;
			this.entryName = entryName;
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.lang.Object#hashCode()
		 */
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result
					+ ((entryName == null) ? 0 : entryName.hashCode());
			result = prime * result
					+ ((jarFileName == null) ? 0 : jarFileName.hashCode());
			return result;
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.lang.Object#equals(java.lang.Object)
		 */
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			JarEntryRecord other = (JarEntryRecord) obj;
			if (entryName == null) {
				if (other.entryName != null)
					return false;
			} else if (!entryName.equals(other.entryName))
				return false;
			if (jarFileName == null) {
				if (other.jarFileName != null)
					return false;
			} else if (!jarFileName.equals(other.jarFileName))
				return false;
			return true;
		}
	}

	/**
	 * @return
	 */
	public File getDependencyOutputFolder() {
		File targetFolder = new File(this.targetDir);
		File dependencyFolder = new File(targetFolder, "dependency");
		return dependencyFolder;
	}

	/**
	 * @throws MojoFailureException
	 * @throws MojoExecutionException
	 */
	public void copyDependencies(File dependencyFolder)
			throws MojoFailureException, MojoExecutionException {
		getLog().info("start to copy dependencies");
		Commandline cl = new Commandline();
		cl.setExecutable("mvn");
		cl.createArg().setValue("clean");
		cl.createArg().setValue("dependency:copy-dependencies");
		cl.createArg().setValue(
				"-DoutputDirectory=" + dependencyFolder.getAbsolutePath());
		cl.createArg().setValue("-Dsilent=true");
		String excludedArtifactIds = this.getTestDependencyArtifactIds();
		if (!excludedArtifactIds.isEmpty()) {
			cl.createArg().setValue(
					"-DexcludeArtifactIds=" + excludedArtifactIds);
			if (debug) {
				getLog().info(
						"====excluded artifact ids: " + excludedArtifactIds);
			}
		} else {
			if (debug) {
				getLog().info("====No excluded artifact ids");
			}
		}
		WriterStreamConsumer systemOut = new WriterStreamConsumer(
				new OutputStreamWriter(System.out));
		int result = -1;
		try {
			result = CommandLineUtils.executeCommandLine(cl, systemOut,
					systemOut);
		} catch (CommandLineException e) {
			String message = "Failed to execute command: " + cl.toString();
			throw new MojoFailureException(message);
		}
		if (result != 0) {
			getLog().error("failed to copy dependencies");
			System.exit(result);
		}
	}

}
