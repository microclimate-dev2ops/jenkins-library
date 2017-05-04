#!groovy

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
    mvnCommands = 'clean package'

-------------------------*/

def call(body) {
  def config = [:]
  // Parameter expansion works after the call to body() below.
  // See https://jenkins.io/doc/book/pipeline/shared-libraries/ 'Defining a more structured DSL'
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  print "microserviceBuilderPipeline: config = ${config}"

  def image = config.image
  def maven = (config.mavenImage == null) ? 'maven:3.5.0-jdk-8' : config.maven
  def docker = (config.dockerImage == null) ? 'docker' : config.docker
  def kubectl = (config.kubectlImage == null) ? 'lachlanevenson/k8s-kubectl:v1.6.0' : config.kubectl
  def mvnCommands = (config.mvnCommands == null) ? 'clean package' : config.mvnCommands
  def registry = System.getenv("REGISTRY").trim()

  podTemplate(
    label: 'msbPod',
    containers: [
      containerTemplate(name: 'maven', image: maven, ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'docker', image: docker, command: 'cat', ttyEnabled: true,
        envVars: [
          containerEnvVar(key: 'DOCKER_API_VERSION', value: '1.23.0')
        ]),
      containerTemplate(name: 'kubectl', image: kubectl, ttyEnabled: true, command: 'cat'),
    ],
    volumes: [
        hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')
    ]
  ){
    node('msbPod') {
      def gitCommit

      stage ('extract') {
        checkout scm
        gitCommit = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
        echo "checked out git commit ${gitCommit}"
      }

      stage ('build') {
        container ('maven') {
          sh "mvn -B ${mvnCommands}"
        }
        container ('docker') {
          sh "docker build -t ${image}:${gitCommit} ."
          if (registry) {
            if (!registry.endsWith('/')) {
              registry = "${registry}/"
            }
            sh "docker tag ${image}:${gitCommit} ${registry}${image}:${gitCommit}"
            sh "docker push ${registry}${appName}:${gitCommit}"
          }
        }
      }

      stage ('deploy') {
        container ('kubectl') {
          sh "find manifests -type f | xargs sed -i \'s|${image}:latest|${image}:${gitCommit}|g\'"
          sh 'kubectl apply -f manifests'
        }
      }
    }
  }
}
