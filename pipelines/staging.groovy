node {
  withEnv(["PATH+NODE=${tool name: 'latest', type: 'jenkins.plugins.nodejs.tools.NodeJSInstallation'}/bin"]) {
    currentBuild.result = "SUCCESS"

    try {
      stage "Set Up"
//        sh "curl -L https://dl.bintray.com/buildit/maven/jenkins-pipeline-libraries-${env.PIPELINE_LIBS_VERSION}.zip -o lib.zip && echo 'A' | unzip lib.zip"

        ecr = load "lib/ecr.groovy"
        git = load "lib/git.groovy"
        npm = load "lib/npm.groovy"
        shell = load "lib/shell.groovy"
        slack = load "lib/slack.groovy"
        convox = load "lib/convox.groovy"
        template = load "lib/template.groovy"

        def domainName = "${env.MONGO_HOSTNAME}".substring(8)
        def registryBase = "006393696278.dkr.ecr.${env.AWS_REGION}.amazonaws.com"
        def registry = "https://${registryBase}"
        def appName = "eolas"
        def mongoUrl = "mongodb://${env.MONGO_HOSTNAME}:27017"
        def serverUrl = "${appName}.staging.${domainName}"
        def serverPort = "80"
        def dbContext = "staging"

        // global for exception handling
        slackChannel = "synapse"
        gitUrl = "https://bitbucket.org/digitalrigbitbucketteam/eolas"
        appUrl = "http://eolas.staging.${domainName}"

      stage "Checkout"
        checkout scm

        // global for exception handling
        shortCommitHash = git.getShortCommit()
        def commitMessage = git.getCommitMessage()
        def version = npm.getVersion()

      stage "Install"
        sh "npm install"

      stage "Build"
        sh "NODE_ENV='staging' npm run validate"

      stage "Package"
        sh "NODE_ENV='development' npm shrinkwrap"
        sh "NODE_ENV='staging' DB_URL='${mongoUrl}' DB_CONTEXT='${dbContext}' SERVER_URL='${serverUrl}' SERVER_PORT='${serverPort}' LOG_LEVEL='INFO' npm run package"
        sh "cd dist; npm install --production"

      stage "Docker Image Build"
        def tag = "${version}-${shortCommitHash}-${env.BUILD_NUMBER}"
        def image = docker.build("${appName}:${tag}", '.')
        ecr.authenticate(env.AWS_REGION)

      stage "Docker Push"
        docker.withRegistry(registry) {
          image.push("${tag}")
        }

      stage "Deploy To AWS"
        def tmpFile = UUID.randomUUID().toString() + ".tmp"
        def ymlData = template.transform(readFile("docker-compose.yml.template"), [tag: tag, registry_base: registryBase, mongo_url: mongoUrl, db_context: dbContext, server_url: serverUrl, server_port: serverPort])
        writeFile(file: tmpFile, text: ymlData)

        sh "convox login ${env.CONVOX_RACKNAME} --password ${env.CONVOX_PASSWORD}"
        sh "convox env set NODE_ENV=staging --app ${appName}-staging"
        sh "convox deploy --app ${appName}-staging --description '${tag}' --file ${tmpFile}"

      stage "Run Functional Acceptance Tests"
        // wait until the app is deployed
        convox.waitUntilDeployed("${appName}-staging")
        convox.ensureSecurityGroupSet("${appName}-staging", env.CONVOX_SECURITYGROUP)
        sh "NODE_ENV='acceptance' npm run accept"

      stage "Promote Build to latest"
        docker.withRegistry(registry) {
          image.push("latest")
        }
        slack.notify("Deployed to Staging", "Commit <${gitUrl}/commits/${shortCommitHash}|${shortCommitHash}> has been deployed to <${appUrl}|${appUrl}>\n\n${commitMessage}", "good", "http://i3.kym-cdn.com/entries/icons/square/000/002/230/42.png", slackChannel)
    }
    catch (err) {
      currentBuild.result = "FAILURE"
      slack.notify("Error while deploying to Staging", "Commit <${gitUrl}/commits/${shortCommitHash}|${shortCommitHash}> failed to deploy to <${appUrl}|${appUrl}>", "danger", "http://i2.kym-cdn.com/entries/icons/original/000/002/325/Evil.jpg", slackChannel)
      throw err
    }
  }
}
