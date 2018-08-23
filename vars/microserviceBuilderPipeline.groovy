#!groovy
// Copyright (c) IBM 2017,2018

/*------------------------
  Typical usage:
  @Library('MicroserviceBuilder') _
  microserviceBuilderPipeline {
    image = 'microservice-test'
  }

  The following parameters may also be specified. Their defaults are shown below.
  These are the names of images to be downloaded from https://hub.docker.com/.

    mavenImage = 'maven:3.5.2-jdk-8'
    dockerImage = 'ibmcom/docker:17.10'
    kubectlImage = 'ibmcom/k8s-kubectl:v1.8.3'
    helmImage = 'lachlanevenson/k8s-helm:v2.7.2'

  You can also specify:

    mvnCommands = 'package'
    build = 'true' - any value other than 'true' == false
    deploy = 'true' - any value other than 'true' == false
    test = 'true' - `mvn verify` is run if this value is `true` and a pom.xml exists
    debug = 'false' - namespaces created during tests are deleted unless this value is set to 'true'
    chartFolder = 'chart' - folder containing helm deployment chart
    manifestFolder = 'manifests' - folder containing kubectl deployment manifests
    namespace = 'targetNamespace' - deploys into Kubernetes targetNamespace.
      Default is to deploy into Jenkins' namespace.
    libertyLicenseJarName - override for Pipeline.LibertyLicenseJar.Name

-------------------------*/

import com.cloudbees.groovy.cps.NonCPS
import java.io.File
import java.util.UUID
import groovy.json.JsonOutput;
import groovy.json.JsonSlurperClassic;

def call(body) {
  def config = [:]
  // Parameter expansion works after the call to body() below.
  // See https://jenkins.io/doc/book/pipeline/shared-libraries/ 'Defining a more structured DSL'
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  print "microserviceBuilderPipeline : config = ${config}"
	
  print "Jess print: $(chartFolder)"

  def image = config.image
  def maven = (config.mavenImage == null) ? 'maven:3.5.2-jdk-8' : config.mavenImage
  def docker = (config.dockerImage == null) ? 'ibmcom/docker:17.10' : config.dockerImage
  def kubectl = (config.kubectlImage == null) ? 'ibmcom/k8s-kubectl:v1.8.3' : config.kubectlImage
  def helm = (config.helmImage == null) ? 'lachlanevenson/k8s-helm:v2.7.2' : config.helmImage
  def mvnCommands = (config.mvnCommands == null) ? 'package' : config.mvnCommands
  def registry = (env.REGISTRY ?: "").trim()
  if (registry && !registry.endsWith('/')) registry = "${registry}/"
  def registrySecret = (env.REGISTRY_SECRET ?: "").trim()
  def build = (config.build ?: env.BUILD ?: "true").toBoolean()
  def deploy = (config.deploy ?: env.DEPLOY ?: "true").toBoolean()
  def namespace = (config.namespace ?: env.NAMESPACE ?: "").trim()
  def serviceAccountName = (env.SERVICE_ACCOUNT_NAME ?: "default").trim()

  // these options were all added later. Helm chart may not have the associated properties set.
  def test = (config.test ?: (env.TEST ?: "false").trim()).toLowerCase() == 'true'
  def debug = (config.debug ?: (env.DEBUG ?: "false").trim()).toLowerCase() == 'true'
  def helmSecret = (env.HELM_SECRET ?: "").trim()
  // will need to check later if user provided chartFolder location
  def userSpecifiedChartFolder = config.chartFolder
  def chartFolder = userSpecifiedChartFolder ?: ((env.CHART_FOLDER ?: "").trim() ?: 'chart')
  def manifestFolder = config.manifestFolder ?: ((env.MANIFEST_FOLDER ?: "").trim() ?: 'manifests')
  def libertyLicenseJarBaseUrl = (env.LIBERTY_LICENSE_JAR_BASE_URL ?: "").trim()
  def libertyLicenseJarName = config.libertyLicenseJarName ?: (env.LIBERTY_LICENSE_JAR_NAME ?: "").trim()
  def alwaysPullImage = (env.ALWAYS_PULL_IMAGE == null) ? true : env.ALWAYS_PULL_IMAGE.toBoolean()
  def mavenSettingsConfigMap = env.MAVEN_SETTINGS_CONFIG_MAP?.trim()
  def helmTlsOptions = " --tls --tls-ca-cert=/msb_helm_sec/ca.pem --tls-cert=/msb_helm_sec/cert.pem --tls-key=/msb_helm_sec/key.pem " 
  def mcReleaseName = (env.RELEASE_NAME).toUpperCase()

  print "microserviceBuilderPipeline: registry=${registry} registrySecret=${registrySecret} build=${build} \
  deploy=${deploy} test=${test} debug=${debug} namespace=${namespace} \
  chartFolder=${chartFolder} manifestFolder=${manifestFolder} alwaysPullImage=${alwaysPullImage} serviceAccountName=${serviceAccountName}"


  def jobName = (env.JOB_BASE_NAME)
  // E.g. JOB_NAME=default/myproject/master
  def jobNameSplit = env.JOB_NAME.split("/")	
  def projectNamespace = jobNameSplit[0]
  def projectName = jobNameSplit[1]
  def branchName = jobNameSplit[2]

  // We won't be able to get hold of registrySecret if Jenkins is running in a non-default namespace that is not the deployment namespace.
  // In that case we'll need the registrySecret to have been ported over, perhaps during pipeline install.

  // Only mount registry secret if it's present
  def volumes = [ hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock') ]
  if (registrySecret) {
    volumes += secretVolume(secretName: registrySecret, mountPath: '/msb_reg_sec')
  }
  if (mavenSettingsConfigMap) {
    volumes += configMapVolume(configMapName: mavenSettingsConfigMap, mountPath: '/msb_mvn_cfg')
  }
  if (helmSecret) {
    volumes += secretVolume(secretName: helmSecret, mountPath: '/msb_helm_sec')
  }
  print "microserviceBuilderPipeline: volumes = ${volumes}"
  print "microserviceBuilderPipeline: helmSecret: ${helmSecret}"

  podTemplate(
    label: 'microclimatePod',
    inheritFrom: 'default',
    serviceAccount: serviceAccountName,
    containers: [
      containerTemplate(name: 'maven', image: maven, ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'docker', image: docker, command: 'cat', ttyEnabled: true,
        envVars: [
          containerEnvVar(key: 'DOCKER_API_VERSION', value: '1.23.0')
        ]),
      containerTemplate(name: 'kubectl', image: kubectl, ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'helm', image: helm, ttyEnabled: true, command: 'cat'),
    ],
    volumes: volumes
  ) {
    node('microclimatePod') {
      def gitCommit
      def previousCommit
      def gitCommitMessage
      def fullCommitID

      print "mcReleaseName=${mcReleaseName} projectNamespace=${projectNamespace} projectName=${projectName} branchName=${branchName}"
      devopsHost = sh(script: "echo \$${mcReleaseName}_IBM_MICROCLIMATE_DEVOPS_SERVICE_HOST", returnStdout: true).trim()	       
      devopsPort = sh(script: "echo \$${mcReleaseName}_IBM_MICROCLIMATE_DEVOPS_SERVICE_PORT", returnStdout: true).trim()	      
      devopsEndpoint = "https://${devopsHost}:${devopsPort}"

      stage ('Extract') {
	  checkout scm
	  fullCommitID = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
	  gitCommit = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
	  previousCommitStatus = sh(script: 'git rev-parse -q --short HEAD~1', returnStatus: true)      
	  // If no previous commit is found, below commands need not run but build should continue
	  // Only run when a previous commit exists to avoid pipeline fail on exit code
	  if (previousCommitStatus == 0){ 
	    previousCommit = sh(script: 'git rev-parse -q --short HEAD~1', returnStdout: true).trim()
	    echo "Previous commit exists: ${previousCommit}"
	  }
	  gitCommitMessage = sh(script: 'git log --format=%B -n 1 ${gitCommit}', returnStdout: true)
	  echo "Checked out git commit ${gitCommit}"
      }

      def imageTag = null
      def helmInitialized = false // Lazily initialize Helm but only once
      if (build) {
        if (fileExists('pom.xml')) {
          stage ('Maven Build') {
            container ('maven') {
              def mvnCommand = "mvn -B"
              if (mavenSettingsConfigMap) {
                mvnCommand += " --settings /msb_mvn_cfg/settings.xml"
              }
              mvnCommand += " ${mvnCommands}"
              sh mvnCommand
            }
          }
        }

        if (fileExists('Dockerfile')) {
          if (fileExists('Package.swift')) {          
            echo "Detected Swift project with a Dockerfile..."
          
            echo "Checking for runtime image..."
            // Remember that grep returns 0 if it's there, 1 if not
            
            def containsRuntimeImage = sh(returnStatus: true, script: "grep 'ibmcom/swift-ubuntu-runtime' Dockerfile")        
            echo "containsRuntimeImage: ${containsRuntimeImage}"
            
            echo "Checking for a build command..."
            def containsBuildCommand = sh(returnStatus: true, script: "grep 'swift build' Dockerfile")
            echo "containsBuildCommand: ${containsBuildCommand}"
            
            echo "Checking for microclimate.override=false..."          
            // Don't do anything with the Dockerfile if we detect this string
            def hasOverride = sh(returnStatus: true, script: "grep 'microclimate.override=false' Dockerfile")
            echo "hasOverride: ${hasOverride}"
          
            // 0 = true, 1 = false! Would be good to use .toBoolean and make this easier to read
            // Modify if there's a runtime image in the FROM, there's no swift build command, there's no override=false
            if (containsRuntimeImage == 0 && containsBuildCommand == 1 && hasOverride == 1) {              
              echo "Modifying the Dockerfile as the Microclimate pipeline has detected the swift-ubuntu-runtime image and no presence of a swift build command in the Dockerfile! Disable this behaviour with microclimate.override=false anywhere in your Dockerfile"                            
              // Use the dev image so we can build
              sh "sed -i 's|FROM ibmcom/swift-ubuntu-runtime|FROM ibmcom/swift-ubuntu|g' Dockerfile"              
              // Add the build command after "COPY . /swift-project"
              sh "sed -i '\\/COPY . \\/swift-project/a RUN cd \\/swift-project && swift build -c release' Dockerfile"
              // Just run the project they've built: replace their cmd with a simpler one
              sh "sed -i 's|cd /swift-project \\&\\& .build-ubuntu/release.*|cd /swift-project \\&\\& swift run\" ]|g' Dockerfile"
              
              def fileContents = sh(returnStdout: true, script: "cat Dockerfile")
              print "Modified Dockerfile is as follows..."
              print "${fileContents}"       
            }
          }
          
          stage ('Docker Build') {
            container ('docker') {
              imageTag = gitCommit
              def buildCommand = "docker build -t ${image}:${imageTag} "
              buildCommand += "--label org.label-schema.schema-version=\"1.0\" "
              def scmUrl = scm.getUserRemoteConfigs()[0].getUrl()
              buildCommand += "--label org.label-schema.vcs-url=\"${scmUrl}\" "
              buildCommand += "--label org.label-schema.vcs-ref=\"${gitCommit}\" "  
              buildCommand += "--label org.label-schema.name=\"${image}\" "
              def buildDate = sh(returnStdout: true, script: "date -Iseconds").trim()
              buildCommand += "--label org.label-schema.build-date=\"${buildDate}\" "
              if (alwaysPullImage) {
                buildCommand += " --pull=true"
              }
              if (previousCommit) {
                buildCommand += " --cache-from ${registry}${image}:${previousCommit}"
              }
              if (libertyLicenseJarBaseUrl) {
                if (readFile('Dockerfile').contains('LICENSE_JAR_URL')) {
                  buildCommand += " --build-arg LICENSE_JAR_URL=" + libertyLicenseJarBaseUrl
                  if (!libertyLicenseJarBaseUrl.endsWith("/")) {
                    buildCommand += "/"
                  }
                  buildCommand += libertyLicenseJarName
                }
              }
              buildCommand += " ."
              if (registrySecret) {
                sh "ln -s /msb_reg_sec/.dockercfg /home/jenkins/.dockercfg"
                sh "mkdir /home/jenkins/.docker"
                sh "ln -s /msb_reg_sec/.dockerconfigjson /home/jenkins/.docker/config.json"
              }
              sh buildCommand
              if (registry) {
                sh "docker tag ${image}:${imageTag} ${registry}${image}:${imageTag}"
                sh "docker push ${registry}${image}:${imageTag}"
              }
            }
          }
        }
      }

      def realChartFolder = null
      if (fileExists(chartFolder)) {
        // find the likely chartFolder location
        realChartFolder = getChartFolder(userSpecifiedChartFolder, chartFolder)
        def yamlContent = "image:"
        yamlContent += "\n  repository: ${registry}${image}"
        if (imageTag) yamlContent += "\n  tag: \\\"${imageTag}\\\""
        sh "echo \"${yamlContent}\" > pipeline.yaml"
      } else if (fileExists(manifestFolder)){
        sh "find ${manifestFolder} -type f | xargs sed -i 's|\\(image:\\s*\\)${image}:latest|\\1${registry}${image}:latest|g'"
        sh "find ${manifestFolder} -type f | xargs sed -i 's|\\(image:\\s*\\)${registry}${image}:latest|\\1${registry}${image}:${gitCommit}|g'"
      }

      if (test && fileExists('pom.xml') && realChartFolder != null && fileExists(realChartFolder)) {
        stage ('Verify') {
          testNamespace = "testns-${env.BUILD_ID}-" + UUID.randomUUID()
          print "testing against namespace " + testNamespace
          String tempHelmRelease = (image + "-" + testNamespace)
          // Name cannot end in '-' or be longer than 53 chars
          while (tempHelmRelease.endsWith('-') || tempHelmRelease.length() > 53) tempHelmRelease = tempHelmRelease.substring(0,tempHelmRelease.length()-1)
          container ('kubectl') {
            sh "kubectl create namespace ${testNamespace}"
            sh "kubectl label namespace ${testNamespace} test=true"
            if (registrySecret) {
              giveRegistryAccessToNamespace (testNamespace, registrySecret)
            }
          }

          if (!helmInitialized) {
            initalizeHelm ()
            helmInitialized = true
          }
          
          container ('helm') {
            def deployCommand = "helm install ${realChartFolder} --wait --set test=true --values pipeline.yaml --namespace ${testNamespace} --name ${tempHelmRelease}"
            if (fileExists("chart/overrides.yaml")) {
              deployCommand += " --values chart/overrides.yaml"
            }
            if (helmSecret) {
              echo "adding --tls"
              deployCommand += helmTlsOptions
            }
            sh deployCommand
          }

          container ('maven') {
            try {
              def mvnCommand = "mvn -B -Dnamespace.use.existing=${testNamespace} -Denv.init.enabled=false"
              if (mavenSettingsConfigMap) {
                mvnCommand += " --settings /msb_mvn_cfg/settings.xml"
              }
              mvnCommand += " verify"
              sh mvnCommand
            } finally {
              step([$class: 'JUnitResultArchiver', allowEmptyResults: true, testResults: '**/target/failsafe-reports/*.xml'])
              step([$class: 'ArtifactArchiver', artifacts: '**/target/failsafe-reports/*.txt', allowEmptyArchive: true])
              if (!debug) {
                container ('kubectl') {
                  if (fileExists(realChartFolder)) {
                    container ('helm') {
                      def deleteCommand = "helm delete ${tempHelmRelease} --purge"
                      if (helmSecret) {
                        echo "adding --tls"
                        deleteCommand += helmTlsOptions
                      }
                      sh deleteCommand
                    }
                  }
		  // Intentionally do this as the final step in here so we can actually delete it
                  // A namespace will not be removed if there's a Kube resource still active in there
                  sh "kubectl delete namespace ${testNamespace}"
                }                
              }
            }
          }
        }
      }

      def result="commitID=${gitCommit}\\n" + 
           "fullCommit=${fullCommitID}\\n" +
           "commitMessage=${gitCommitMessage}\\n" + 
           "registry=${registry}\\n" + 
           "image=${image}\\n" + 
           "imageTag=${imageTag}"
      
      sh "echo '${result}' > buildData.txt"
      archiveArtifacts 'buildData.txt'

      if (deploy) {
        if (!helmInitialized) {
          initalizeHelm ()
          helmInitialized = true
        }
        notifyDevops(gitCommit, fullCommitID, registry + image, imageTag, 
          branchName, "build", projectName, projectNamespace, env.BUILD_NUMBER.toInteger())
      }
    }
  }
}

def notifyDevops (String gitCommit, String fullCommitID, String image, 
  String imageTag, String branchName, String triggerType, String projectName, String projectNamespace, Integer buildNumber) {

  notificationEndpoint="${devopsEndpoint}/v1/namespaces/${projectNamespace}/projects/${projectName}/notifications"	

  stage ('Notify Devops') {	  
    print "Poking the notification API at ${notificationEndpoint}, parameters..."	

    print "gitCommit=${gitCommit}, fullCommitID=${fullCommitID}, image: ${image} \
      imageTag=${imageTag}, branchName=${branchName}, triggerType=${triggerType} \
      buildNumber=${buildNumber}"

    def notificationData = [
      chart: [gitCommit: gitCommit, fullCommit: fullCommitID],
      overrides: [image: [repository: image, tag: imageTag]],
      trigger: [type: triggerType, branch: branchName],
      clusterConfigSecret: "",
      namespace: "",
      status: "",
      buildNumber: buildNumber
    ]

    def payload = JsonOutput.toJson(notificationData)
    notification = [ 'bash', '-c', "curl -v -k -X POST -H \"Content-Type: application/json\" -d '${payload}' $notificationEndpoint" ].execute().text
    print "Devops notification response: ${notification}"
  }
}

def initalizeHelm () {
  container ('helm') {
    sh "helm init --skip-refresh --client-only"     
  }
}

def deployProject (String chartFolder, String registry, String image, String imageTag, String namespace, String manifestFolder, String registrySecret, String helmSecret, String helmTlsOptions) {
  if (chartFolder != null && fileExists(chartFolder)) {
    container ('helm') {
      def deployCommand = "helm upgrade --install --wait --values pipeline.yaml"
      if (fileExists("chart/overrides.yaml")) {
        deployCommand += " --values chart/overrides.yaml"
      }
      if (namespace) {
        deployCommand += " --namespace ${namespace}"
        createNamespace(namespace, registrySecret)   
      }
      if (helmSecret) {
        echo "adding --tls"
        deployCommand += helmTlsOptions
      }
      def releaseName = (env.BRANCH_NAME == "master") ? "${image}" : "${image}-${env.BRANCH_NAME}"
      deployCommand += " ${releaseName} ${chartFolder}"
      sh deployCommand
    }
  } else if (fileExists(manifestFolder)) {
    container ('kubectl') {
      def deployCommand = "kubectl apply -f ${manifestFolder}"
      if (namespace) {
        createNamespace(namespace, registrySecret)
        deployCommand += " --namespace ${namespace}"
      }
      sh deployCommand
    }
  }
}

/*
  Create target namespace and give access to regsitry
*/
def createNamespace(String namespace, String registrySecret) {
  container ('kubectl') {
    ns_exists = sh(returnStatus: true, script: "kubectl get namespace ${namespace}")
    if (ns_exists != 0) {
      sh "kubectl create namespace ${namespace}"
      if (registrySecret) {
        giveRegistryAccessToNamespace (namespace, registrySecret)
      }
    }
  }
}

/*
  We have a (temporary) namespace that we want to grant ICP registry access to.
  String namespace: target namespace

  1. Port registrySecret into a temporary namespace
  2. Modify 'default' serviceaccount to use ported registrySecret.
*/

def giveRegistryAccessToNamespace (String namespace, String registrySecret) {
  sh "kubectl get secret ${registrySecret} -o json | sed 's/\"namespace\":.*\$/\"namespace\": \"${namespace}\",/g' | kubectl create -f -"
  sh "kubectl patch serviceaccount default -p '{\"imagePullSecrets\": [{\"name\": \"${registrySecret}\"}]}' --namespace ${namespace}"
}

def getChartFolder(String userSpecified, String currentChartFolder) {

  def newChartLocation = ""
  if (userSpecified) {
    print "User defined chart location specified: ${userSpecified}"
    return userSpecified
  } else {
    print "Finding actual chart folder below ${env.WORKSPACE}/${currentChartFolder}..."
    def fp = new hudson.FilePath(Jenkins.getInstance().getComputer(env['NODE_NAME']).getChannel(), env.WORKSPACE + "/" + currentChartFolder)
    def dirList = fp.listDirectories()
    if (dirList.size() > 1) {
      print "More than one directory in ${env.WORKSPACE}/${currentChartFolder}..."
      print "Directories found are:"
      def yamlList = []
      for (d in dirList) {
        print "${d}"
        def fileToTest = new hudson.FilePath(d, "Chart.yaml")
        if (fileToTest.exists()) {
          yamlList.add(d)
        }
      }
      if (yamlList.size() > 1) {
        print "-----------------------------------------------------------"
        print "*** More than one directory with Chart.yaml in ${env.WORKSPACE}/${currentChartFolder}."
        print "*** Please specify chart folder to use in your Jenkinsfile."
        print "*** Returning null."
        print "-----------------------------------------------------------"
        return null
      } else {
        if (yamlList.size() == 1) {
          newChartLocation = currentChartFolder + "/" + yamlList.get(0).getName()
          print "Chart.yaml found in ${newChartLocation}, setting as realChartFolder"
          return newChartLocation
        } else {
          print "-----------------------------------------------------------"
          print "*** No sub directory in ${env.WORKSPACE}/${currentChartFolder} contains a Chart.yaml, returning null"
          print "-----------------------------------------------------------"
          return null
        }
      }
    } else {
      if (dirList.size() == 1) {
        def chartFile = new hudson.FilePath(dirList.get(0), "Chart.yaml")
        newChartLocation = currentChartFolder + "/" + dirList.get(0).getName()
        if (chartFile.exists()) {
          print "Only one child directory found, setting realChartFolder to: ${newChartLocation}"
          return newChartLocation
        } else {
          print "-----------------------------------------------------------"
          print "*** Chart.yaml file does not exist in ${newChartLocation}, returning null"
          print "-----------------------------------------------------------"
          return null
        }
      } else {
        print "-----------------------------------------------------------"
        print "*** Chart directory ${env.WORKSPACE}/${currentChartFolder} has no subdirectories, returning null"
        print "-----------------------------------------------------------"
        return null
      }
    }
  }
}
