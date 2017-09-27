#!groovy
// Copyright (c) IBM 2017

/*------------------------
  Typical usage:
  @Library('MicroserviceBuilder') _
  microserviceBuilderPipeline {
    image = 'microservice-test'
  }

  The following parameters may also be specified. Their defaults are shown below.
  These are the names of images to be downloaded from https://hub.docker.com/.

    mavenImage = 'maven:3.5.0-jdk-8'
    dockerImage = 'docker'
    kubectlImage = 'lachlanevenson/k8s-kubectl:v1.6.0'
    helmImage = 'lachlanevenson/k8s-helm:v2.4.1'
    istioctlImage = 'ibmcom/istioctl:1.6.0'

  You can also specify:

    mvnCommands = 'clean package'
    build = 'true' - any value other than 'true' == false
    deploy = 'true' - any value other than 'true' == false
    test = 'true' - `mvn verify` is run if this value is `true` and a pom.xml exists
    debug = 'false' - namespaces created during tests are deleted unless this value is set to 'true'
    deployBranch = 'master' - only builds from this branch are deployed
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

  def image = config.image
  def maven = (config.mavenImage == null) ? 'maven:3.5.0-jdk-8' : config.mavenImage
  def docker = (config.dockerImage == null) ? 'docker' : config.dockerImage
  def kubectl = (config.kubectlImage == null) ? 'lachlanevenson/k8s-kubectl:v1.6.0' : config.kubectlImage
  def helm = (config.helmImage == null) ? 'lachlanevenson/k8s-helm:v2.4.1' : config.helmImage
  def istioctl = (config.istioctlImage == null) ? 'ibmcom/istioctl:1.6.0' : config.istioctlImage
  def mvnCommands = (config.mvnCommands == null) ? 'clean package' : config.mvnCommands
  def registry = System.getenv("REGISTRY").trim()
  if (registry && !registry.endsWith('/')) registry = "${registry}/"
  def registrySecret = System.getenv("REGISTRY_SECRET").trim()
  def build = (config.build ?: System.getenv ("BUILD")).trim().toLowerCase() == 'true'
  def deploy = (config.deploy ?: System.getenv ("DEPLOY")).trim().toLowerCase() == 'true'
  def namespace = config.namespace ?: (System.getenv("NAMESPACE") ?: "").trim()

  // these options were all added later. Helm chart may not have the associated properties set.
  def test = (config.test ?: (System.getenv ("TEST") ?: "false").trim()).toLowerCase() == 'true'
  def debug = (config.debug ?: (System.getenv ("DEBUG") ?: "false").trim()).toLowerCase() == 'true'
  def deployBranch = config.deployBranch ?: ((System.getenv("DEFAULT_DEPLOY_BRANCH") ?: "").trim() ?: 'master')
  // will need to check later if user provided chartFolder location
  def userSpecifiedChartFolder = config.chartFolder
  def chartFolder = userSpecifiedChartFolder ?: ((System.getenv("CHART_FOLDER") ?: "").trim() ?: 'chart')
  def manifestFolder = config.manifestFolder ?: ((System.getenv("MANIFEST_FOLDER") ?: "").trim() ?: 'manifests')
  def libertyLicenseJarBaseUrl = (System.getenv("LIBERTY_LICENSE_JAR_BASE_URL") ?: "").trim()
  def libertyLicenseJarName = config.libertyLicenseJarName ?: (System.getenv("LIBERTY_LICENSE_JAR_NAME") ?: "").trim()

  print "microserviceBuilderPipeline: registry=${registry} registrySecret=${registrySecret} build=${build} \
  deploy=${deploy} deployBranch=${deployBranch} test=${test} debug=${debug} namespace=${namespace} \
  chartFolder=${chartFolder} manifestFolder=${manifestFolder}"

  // We won't be able to get hold of registrySecret if Jenkins is running in a non-default namespace that is not the deployment namespace.
  // In that case we'll need the registrySecret to have been ported over, perhaps during pipeline install.

  // Only mount registry secret if it's present
  def volumes = [ hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock') ]
  if (registrySecret) {
    volumes += secretVolume(secretName: registrySecret, mountPath: '/msb_reg_sec')
  }
  print "microserviceBuilderPipeline: volumes = ${volumes}"

  testNamespace = "testns-${env.BUILD_ID}-" + UUID.randomUUID()
  print "testing against namespace " + testNamespace

    podTemplate(
    label: 'msbPod',
    containers: [
      containerTemplate(name: 'maven', image: maven, ttyEnabled: true, command: 'cat',
        envVars: [
          containerEnvVar(key: 'ENV_INIT_ENABLED', value: 'false'),
          containerEnvVar(key: 'NAMESPACE_USE_EXISTING', value: testNamespace)
        ]),
      containerTemplate(name: 'docker', image: docker, command: 'cat', ttyEnabled: true,
        envVars: [
          containerEnvVar(key: 'DOCKER_API_VERSION', value: '1.23.0')
        ]),
      containerTemplate(name: 'kubectl', image: kubectl, ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'helm', image: helm, ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'istioctl', image: istioctl, ttyEnabled: true, command: 'cat')
    ],
    volumes: volumes
  ) {
    node('msbPod') {
      def gitCommit

      stage ('Extract') {
        checkout scm
        gitCommit = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
        echo "checked out git commit ${gitCommit}"
      }

      def imageTag = null
      if (build) {
        if (fileExists('pom.xml')) {
          stage ('Maven Build') {
            container ('maven') {
              sh "mvn -B ${mvnCommands}"
            }
          }
        }
        if (fileExists('Dockerfile')) {
          stage ('Docker Build') {
            container ('docker') {
              imageTag = gitCommit
              def buildCommand = "docker build --pull=true -t ${image}:${imageTag}"
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
      } else {
        sh "find ${manifestFolder} -type f | xargs sed -i \'s|\\(image:\\s*\\)\\(.*\\):latest|\\1${registry}\\2:${gitCommit}|g\'"
      }

      if (test && fileExists('pom.xml') && realChartFolder != null && fileExists(realChartFolder)) {
        stage ('Verify') {
          String tempHelmRelease = (image + "-" + testNamespace)
          if (tempHelmRelease.length() > 53) tempHelmRelease = tempHelmRelease.substring(0,52) // 53 is max length in Helm
          container ('kubectl') {
            sh "kubectl create namespace ${testNamespace}"
            sh "kubectl label namespace ${testNamespace} test=true"
            if (registrySecret) {
              giveRegistryAccessToNamespace (testNamespace, registrySecret)
            }
          }
          // We're moving to Helm-only deployments. Use Helm to install a deployment to test against.
          container ('helm') {
            sh "helm init --client-only"
            def deployCommand = "helm install ${realChartFolder} --wait --set test=true,image.repository=${registry}${image},image.tag=${imageTag} --namespace ${testNamespace} --name ${tempHelmRelease}"
            if (fileExists("chart/overrides.yaml")) {
              deployCommand += " --values chart/overrides.yaml"
            }
            sh deployCommand
          }

          container ('maven') {
            try {
              sh "mvn -B verify"
            } finally {
              step([$class: 'JUnitResultArchiver', allowEmptyResults: true, testResults: '**/target/failsafe-reports/*.xml'])
              step([$class: 'ArtifactArchiver', artifacts: '**/target/failsafe-reports/*.txt', allowEmptyArchive: true])
              if (!debug) {
                container ('kubectl') {
                  sh "kubectl delete namespace ${testNamespace}"
                  if (fileExists(realChartFolder)) {
                    container ('helm') {
                      sh "helm delete ${tempHelmRelease} --purge"
                    }
                  }
                }
              }
            }
          }
        }
      }

      if (deploy && env.BRANCH_NAME == deployBranch) {
        stage ('Deploy') {
          deployProject (realChartFolder, registry, image, imageTag, namespace, manifestFolder)
        }
      }

      if (fileExists('istio.yaml')) {
        container ('istioctl') {
          try {
            sh (script: "istioctl replace -f istio.yaml", returnStdout: true).trim()
          } catch (Exception x) {
            sh "istioctl create -f istio.yaml"
          }
        }
      }
    }
  }
}

def deployProject (String chartFolder, String registry, String image, String imageTag, String namespace, String manifestFolder) {
  if (chartFolder != null && fileExists(chartFolder)) {
    container ('helm') {
      sh "helm init --client-only"
      def deployCommand = "helm upgrade --install --set image.repository=${registry}${image}"
      if (imageTag) deployCommand += ",image.tag=${imageTag}"
      if (fileExists("chart/overrides.yaml")) {
        deployCommand += " --values chart/overrides.yaml"
      }
      if (namespace) deployCommand += " --namespace ${namespace}"
      def releaseName = (env.BRANCH_NAME == "master") ? "${image}" : "${image}-${env.BRANCH_NAME}"
      deployCommand += " ${releaseName} ${chartFolder}"
      sh deployCommand
    }
  } else if (fileExists(manifestFolder)) {
    container ('kubectl') {
      def deployCommand = "kubectl apply -f ${manifestFolder}"
      if (namespace) deployCommand += " --namespace ${namespace}"
      sh deployCommand
    }
  }
}

/*
  We have a (temporary) namespace that we want to grant CfC registry access to.
  String namespace: target namespace
  String registrySecret: secret in Jenkins' namespace to use

  1. Port registrySecret into namespace
  2. Modify 'default' serviceaccount to use ported registrySecret.
*/

def giveRegistryAccessToNamespace (String namespace, String registrySecret) {
  String secretScript = "kubectl get secret/${registrySecret} -o jsonpath=\"{.data.\\.dockercfg}\""
  String secret = sh (script: secretScript, returnStdout: true).trim()
  String yaml = """
  apiVersion: v1
  data:
    .dockercfg: ${secret}
  kind: Secret
  metadata:
    name: ${registrySecret}
  type: kubernetes.io/dockercfg
  """
  sh "printf -- \"${yaml}\" | kubectl apply --namespace ${namespace} -f -"

  String sa = sh (script: "kubectl get sa default -o json --namespace ${namespace}", returnStdout: true).trim()
  /*
      Use JsonSlurperClassic because JsonSlurper is not thread safe, not serializable, and not good to use in Jenkins jobs.
      See https://stackoverflow.com/questions/37864542/jenkins-pipeline-notserializableexception-groovy-json-internal-lazymap
  */
  def map = new JsonSlurperClassic().parseText (sa)
  map.metadata.remove ('resourceVersion')
  map.put ('imagePullSecrets', [['name': registrySecret]])
  def json = JsonOutput.prettyPrint(JsonOutput.toJson(map))
  writeFile file: 'temp.json', text: json
  sh "kubectl replace sa default --namespace ${namespace} -f temp.json"
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
