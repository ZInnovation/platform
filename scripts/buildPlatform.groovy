import groovy.io.FileType
import groovy.util.XmlParser
import groovy.xml.XmlUtil

@Grab('net.sf.jopt-simple:jopt-simple:4.6')
import joptsimple.OptionParser

@Grab('org.yaml:snakeyaml:1.13')
import org.yaml.snakeyaml.Yaml

def getRootDir() {
	@groovy.transform.SourceURI
	def source
	new File(source).parentFile.parentFile
}

def execute(def command, def workingDirectory, def continueOnFailure, def environment) {
	def builder = new ProcessBuilder(command.split(' ')).directory(workingDirectory as File).inheritIO()
	builder.environment().putAll(environment)
	def process = builder.start()
	if (process.waitFor()) {
		println "'$command' failed"
		if (!continueOnFailure) {
			System.exit(1)
		} else {
			false
		}
	} else {
		true
	}
}

def execute(def command) {
	execute(command, null)
}

def execute(def command, def workingDirectory) {
	execute(command, workingDirectory, false, [:])
}

def build(def project, def platformVersion, String jdk7Home, String jdk8Home) {
	println "Building $project.name"
	println "Cloning $project.build.repository"
	def projectName = project.name.toLowerCase().replace(' ', '-')
	def dir = "$rootDir/build/$projectName"
	execute("git clone $project.build.repository $dir")
	def checkout = project.build['checkout'];
	if (!checkout && !project.version.endsWith('SNAPSHOT')) {
		def prefix = project.build.containsKey('tagPrefix') ? project.build['tagPrefix']: 'v'
		if (!prefix) {
			prefix = ''
		}
		checkout = "${prefix}${project.version}"
	}
	if (checkout) {
		execute("git checkout $checkout", dir)
	}

	File patchesDir = new File("$rootDir/patches/$projectName")
	if (patchesDir.exists()) {
		patchesDir.eachFile(FileType.FILES) { file ->
			execute("git am --ignore-whitespace ${file.absolutePath}", dir)
		}
	}

	if (project.build['directory']) {
		dir = "$dir/${project.build['directory']}"
	}

	def command = "./gradlew -I $initScriptPath clean"

	if (project.build.containsKey('additional_tasks')) {
		command += " ${project.build['additional_tasks']}"
	}
	command += " springIoCheck -PplatformVersion=$platformVersion -PrepositoryDir=$rootDir/build/repository --continue --refresh-dependencies --stacktrace"

	def runTestsWith = project.build['runTestsWith'] ?: ['jdk7', 'jdk8']
	if (!runTestsWith) {
		runTestsWith = ['jdk7', 'jdk8']
	}

	if (runTestsWith.contains('jdk7') && jdk7Home) {
		command += " -PJDK7_HOME=$jdk7Home"
	}

	if (runTestsWith.contains('jdk8') && jdk8Home) {
		command += " -PJDK8_HOME=$jdk8Home"
	}

	def compileWith = project.build['compileWith'] ?: 'jdk7'

	def environment = [:]

	if (compileWith == 'jdk7') {
		environment['JAVA_HOME'] = jdk7Home
	} else {
		environment['JAVA_HOME'] = jdk8Home
	}

	execute(command, dir, true, environment)
}

def parseArguments(def args) {
	def optionParser = new OptionParser();
	optionParser.accepts("jdk7-home", "The path to the home directory of JDK 7").withRequiredArg()
	optionParser.accepts("jdk8-home", "The path to the home directory of JDK 8").withRequiredArg()
	optionParser.accepts("project", "The name of a project to build").withRequiredArg()

	try {
		optionParser.parse(args)
	} catch (Exception e) {
		showUsageAndExit(optionParser)
	}
}

def showUsageAndExit(def optionParser) {
	optionParser.printHelpOn(System.out)
	System.exit 1
}

def getInitScriptPath() {
	@groovy.transform.SourceURI
	def scriptLocation
	new File (new File(scriptLocation).parentFile, 'configureRepositories.gradle').absolutePath
}

def generateJunitReport(File buildDir) {
	new File("$rootDir/build").eachFileRecurse(FileType.DIRECTORIES) { dir ->
		if (dir.name == "spring-io-jdk7-test-results") {
			postProcessResults(dir, " (Java 7)")
		}
		else if (dir.name == "spring-io-jdk8-test-results") {
			postProcessResults(dir, " (Java 8)")
		}
	}
	def ant = new AntBuilder()
	ant.junitreport(todir:"$buildDir") {
		fileset(dir:"$buildDir") {
			include(name:'**/build/spring-io-*-test-results/TEST-*.xml')
		}
		report(todir:"$buildDir/spring-io-test-results")
	}
}

def postProcessResults(File resultsDir, String nameSuffix) {
	resultsDir.eachFileMatch(FileType.FILES, ~/TEST-.+\.xml/) { resultXmlFile ->
		testsuite = new XmlParser().parse(resultXmlFile)
		applySuffix(testsuite, 'name', nameSuffix)
		testsuite.testcase.each { testcase ->
			applySuffix(testcase, 'name', nameSuffix)
			applySuffix(testcase, 'classname', nameSuffix)
		}
		resultXmlFile.text = XmlUtil.serialize(testsuite)
	}
}

def applySuffix(def node, def attribute, String suffix) {
	node.attributes()[attribute] = node.attributes()[attribute] + suffix
}

def options = parseArguments(args)
def yaml = new Yaml().load(new File(rootDir, 'platform-definition.yaml').text)
def projects = yaml['platform_definition']['projects']

def selectedProjects = options.valuesOf('project')

def problems = [] as Set

if (selectedProjects) {
	projects = projects.findAll { selectedProjects.contains(it.name) }
	if (!projects) {
		problems << "No matching projects were found"
	}
}

projects.each {
	jdks = []
	jdks += it.build?.'compileWith' ?: 'jdk7'
	jdks += it.build?.'runTestsWith' ?: 'jdk7'

	if (jdks.contains('jdk7') && !options.has('jdk7-home')) {
		problems << "Platform definition requires Java 7. Please use --jdk7-home to provide it"
	}
	if (jdks.contains('jdk8') && !options.has('jdk8-home')) {
		problems << "Platform definition requires Java 8. Please use --jdk8-home to provide it"
	}
}

if (problems) {
	println ""
	problems.each {
		println it
	}
	println "\n\033[31mBUILD FAILED\033[0m\n"
} else {
	def buildDir = new File(rootDir, 'build')
	buildDir.deleteDir()

	def platformVersions = new File(rootDir, 'platform-bom/target/platform-bom.properties')
	def dir = new File(buildDir, 'repository/io/spring/platform/platform-versions/LOCALTEST')
	dir.mkdirs()
	new File(dir, 'platform-versions-LOCALTEST.properties') << platformVersions.text

	def platformBom = new XmlParser().parseText(new File(rootDir, 'platform-bom/pom.xml').text)
	platformBom.version[0].replaceNode { version("LOCALTEST") }
	dir = new File(buildDir, 'repository/io/spring/platform/platform-bom/LOCALTEST')
	dir.mkdirs()
	new File(dir, 'platform-bom-LOCALTEST.pom') << XmlUtil.serialize(platformBom)

	def jdk7Home = options.valueOf('jdk7-home')
	def jdk8Home = options.valueOf('jdk8-home')
	def buildFailures = projects.findAll{ it.build }
			.findAll{ !build(it, 'LOCALTEST', jdk7Home, jdk8Home) }
			.collect{ it.name }

	generateJunitReport(buildDir)

	if (buildFailures) {
		println "\n\033[31mBUILD FAILED\033[0m\n"
		println "The following projects did not build cleanly:"
		buildFailures.each { println "    $it"}
		println "\nExamine the output above and the test results for further details"
		System.exit(1)
	}
}

