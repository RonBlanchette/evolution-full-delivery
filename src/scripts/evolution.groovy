import groovy.json.JsonSlurperClassic
import groovy.json.JsonSlurper

def jsonFile = libraryResource 'migration_delivery.json'
def json = new JsonSlurperClassic().parseText(jsonFile)


// define input parameters
properties([
	parameters(
		[
			booleanParam(defaultValue: false, description: 'check this is you only need to refresh the choices of the pipeline after a change in the source control.', name: 'ONLY_REFRESH_CHOICES'),
			[$class            : 'ExtensibleChoiceParameterDefinition',
			 choiceListProvider: [$class: 'TextareaChoiceListProvider', addEditedValue: true, choiceListText: json.projects.artifactId.unique().join("\n"), whenToAdd: 'CompletedStable'],
			 description       : '',
			 editable          : true,
			 name              : 'DEPLOYMENT'
			],
			[$class            : 'ExtensibleChoiceParameterDefinition',
			 choiceListProvider: [$class: 'TextareaChoiceListProvider', addEditedValue: true, choiceListText: json.includes.unique().join("\n"), whenToAdd: 'CompletedStable'],
			 description       : '',
			 editable          : true,
			 name              : 'includes'
			],
			//choice(choices: rmtools_environmentArray.join("\n\n"), description: '', name: 'rmtools_environment'),
			[$class            : 'ExtensibleChoiceParameterDefinition',
			 choiceListProvider: [$class: 'TextareaChoiceListProvider', addEditedValue: true, choiceListText: json.environments.unique().join("\n"), whenToAdd: 'CompletedStable'],
			 description       : '',
			 editable          : true,
			 name              : 'rmtools_environment'
			],
			[$class            : 'ExtensibleChoiceParameterDefinition',
			 choiceListProvider: [$class: 'TextareaChoiceListProvider', addEditedValue: true, choiceListText: json.environment_letter.unique().join("\n"), whenToAdd: 'CompletedStable'],
			 description       : '',
			 editable          : true,
			 name              : 'rmtools_rmtools_environment_letter'
			],
			booleanParam(defaultValue: false, description: 'skips all &lt;EAR&gt; and &lt;PACKAGE&gt; but keeps &lt;PROP&gt;.YOU MUST set INCLUDES to ALL', name: 'rmtools_onlyProps'),
			booleanParam(defaultValue: false, description: 'activates the delivery deployment', name: 'rmtools_deploy_after_upload'),
			booleanParam(defaultValue: false, description: 'activates FAKE delivery to RMTools.Safe way to test the execution', name: 'dryRun'),
			booleanParam(defaultValue: false, description: 'Sends notification to the specified group', name: 'rmtools_sendMail'),
			booleanParam(defaultValue: true, description: 'Display extra information for debugging', name: 'Debug_mode'),
			string(defaultValue: 'now', description: 'deployment time : now, now+3:00, 19:15', name: 'rmtools_deploytime'),
			string(defaultValue: '${project.version}', description: '', name: 'delivery_app_version'),
			string(defaultValue: '${project.version}', description: '', name: 'delivery_prop_version'),
			[$class: 'WHideParameterDefinition', defaultValue: '', description: '', name: 'DESCRIPTION']
		])
])
if (!ONLY_REFRESH_CHOICES.toBoolean()) {
	node('node-2020-002') {

		String selectedProfiles
		String GIT_URL
		String DELIVERYPATH
		String version_APP
		String version_PROP
		String selectedPOM
		String rmtools_emailTo
		String delivery_path_message
		// template definition
		def templateParameters = []

		// Get delivery information from the json file using the ArtifactId
		project = retrieveProjectFromArtifactId(DEPLOYMENT, json)
		selectedProfiles = project.profile
		GIT_URL = project.git_url
		DELIVERYPATH = project.delivery_path
		selectedPOM = project.pom
		rmtools_emailTo = project.emails

		stage('retrieve parameters') {
		}

		stage('checkout repo') {
			// Get the project from git
			withCredentials([usernamePassword(credentialsId: '0afacacb-18d1-4b9a-a0db-d2c44495bae8', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
				git branch: 'master', credentialsId: '0afacacb-18d1-4b9a-a0db-d2c44495bae8', url: "${GIT_URL}"

				bat 'dir'
				pom = readMavenPom file: 'pom.xml'

				// App version override check
				if (delivery_app_version=='${project.version}') {
					// use the version from the project pom
					version_APP = pom.version
				} else {
					// use the overide version of the app
					version_APP = "${delivery_app_version}"
				}

				// Prop version override check
				if (delivery_prop_version=='${project.version}') {
					// use the version from the project pom
					version_PROP = pom.version
				} else {
					// use the overide version of the props
					version_PROP = "${delivery_prop_version}"
				}
			}
		}

		// did we find the delivery path
		stage('check if delivery exists') {
			if (fileExists("${DELIVERYPATH}")) {
				delivery_path_message ="${DELIVERYPATH} exists!!!"
			} else {
				delivery_path_message="${DELIVERYPATH} not found"
			}
		}

		stage('build paramaters'){

			// append template paramaters
			templateParameters << "-f ${selectedPOM}"
			templateParameters << "-s F:\\Data\\var\\maven\\settingsDelivery.xml"
			templateParameters << "--toolchains %MAVEN_TOOLCHAINS%"
			templateParameters << "-Drmtools-environment-letter=${rmtools_rmtools_environment_letter}"
			templateParameters << "-Drmtools.onlyProps=${rmtools_onlyProps}"
			templateParameters << "-DdryRun=${dryRun}"
			templateParameters << "-Drmtools-environment=${rmtools_environment}"
			templateParameters << "-Ddelivery-version=${version_APP}"
			templateParameters << "-Dincludes=${includes}"
			templateParameters << "-DselectedProfiles=${selectedProfiles}"
			templateParameters << "-Ddelivery-prop-version=${version_PROP}"
			templateParameters << "-Drmtools-deploytime=${rmtools_deploytime}"
			templateParameters << "-Drmtools-deploy-after-upload=${rmtools_deploy_after_upload}"
			templateParameters << "-Drmtools.storeDeliveries=true"
			templateParameters << "-Drmtools.includeOnly=${includes}"
			templateParameters << "-DmavenRMToolsPluginVersion=2.3.5"   // should be parameterized in json file
			templateParameters << "-Duser.home=F:\\Data\\var\\maven"
			templateParameters << " -Drmtools.skip=false"
			templateParameters << "-DoverrideDeliveryVersion=true"
			templateParameters << "clean"
			templateParameters << "deploy"
			templateParameters << "rmtools:aggregate-reports"
			templateParameters << "-P ${selectedProfiles}"

		}
		// display stage - used for debugging
		stage('display values') {

			// only display if debug mode is selected
			if (Debug_mode.toBoolean()) {

				// Section heading
				println(" *********************** \n ** PARAMATER VALUES  ** \n ***********************")

				// Did we find a delivery path (return variable)
				println("${delivery_path_message}")

				// display build paramaters
				println("template parameters: \n\n ${templateParameters.join("\n")} \n")

				// Build input values
				println("DEPLOYMENT = ${DEPLOYMENT}")
				println("selectedProfiles = ${selectedProfiles}")
				println("profile.git_url from json = ${project.git_url}")
				println("GIT_URL = ${GIT_URL}")
				println("profile.delivery_path from json = ${project.delivery_path}")
				println("DELIVERYPATH = ${DELIVERYPATH}")

				println("includes = ${includes}")
				println("rmtools_environment = ${rmtools_environment}")
				println("rmtools_deploytime = ${rmtools_deploytime}")
				println("rmtools_rmtools_environment_letter = ${rmtools_rmtools_environment_letter}")
				println("rmtools_emailTo = ${rmtools_emailTo}")
				println("rmtools_onlyProps = ${rmtools_onlyProps}")
				println("rmtools_deploy_after_upload = ${rmtools_deploy_after_upload}")
				println("dryRun = ${dryRun}")
				println("Debug_mode = ${Debug_mode}")
				println("rmtools_sendMail = ${rmtools_sendMail}")
				println("rmtools_deploytime = ${rmtools_deploytime}")
				println("selectedPOM = ${selectedPOM}")
				println("delivery_app_version = ${delivery_app_version}")
				println("delivery_prop_version = ${delivery_prop_version}")
				println("version_APP = ${version_APP}")
				println("version_PROP = ${version_PROP}")
				println("profile.pom from json = ${project.pom}")
				println("${json}")
				println("profile.project from json = ${project.profile}")

			} else {
				println('Set Debug Mode to true to display more values.')
			}
		}

		stage('run maven delivery') {
			JAVA_HOME = tool 'Oracle JDK 1.8.0_25 Windows'
			MAVEN_HOME = tool 'maven-3.0.5'
			withEnv(["JAVA_HOME=${JAVA_HOME}", "MAVEN_HOME=${MAVEN_HOME}", "PATH+JAVA_HOME=${JAVA_HOME}\\bin", "PATH+MAVEN_HOME=${MAVEN_HOME}\\bin"]) {
				bat 'mvn --version'
				// run the maven command to deploy application with parameters
				bat "mvn ${templateParameters.join(' ')}"
			}

		}
	}
} else {
	currentBuild.displayName = 'REFRESHED CHOICES'
}

def retrieveProjectFromArtifactId(final String artifactId, final json) {
	assert artifactId && json:"params has not to be null or empty"
	def project=json.projects.find({p-> return p.artifactId.equals(artifactId)})
	assert project != null : "artifact + [${artifactId}] not found in shared library"

	return project
}




