def label = "Lostphone-${UUID.randomUUID().toString()}"
def isIpcRunningEnv = true
def isEpcRunningEnv = false

String getBranchName(branch) {
    branchTemp=sh returnStdout:true ,script:"""echo "$branch" |sed -E "s#origin/##g" """
    if(branchTemp){
        branchTemp=branchTemp.trim()
    }
    return branchTemp
}


podTemplate(cloud:'c02-okd4-cz-tb',label: label, serviceAccount: 'default', namespace: 'lostphone',
    containers: [
        containerTemplate(name: 'build-tools', image: 'ktis-bastion01.container.ipc.kt.com:5000/alpine/build-tools:v3.2', ttyEnabled: true, command: 'cat', privileged: true, alwaysPullImage: true),
        containerTemplate(name: 'node', image: 'ktis-bastion01.container.ipc.kt.com:5000/admin/node:18.16.0', ttyEnabled: true, command: 'cat', privileged: true, alwaysPullImage: true),
        containerTemplate(name: 'jnlp', image: 'ktis-bastion01.container.ipc.kt.com:5000/jenkins/jnlp-slave:alpine', args: '${computer.jnlpmac} ${computer.name}'),
    ],
    volumes: [
        hostPathVolume(hostPath: '/etc/mycontainers', mountPath: '/var/lib/containers'),
        nfsVolume(mountPath: '/home/jenkins', serverAddress: '10.217.166.126', serverPath: '/jenkins-slave-pv', readOnly: false)
    ]
    ) {
    node(label) {
        if ( isIpcRunningEnv ) {
        }
        try {
            // freshStart 
            def freshStart = params.freshStart

            if ( freshStart ) {
                container('build-tools'){
                    // remove previous working dir
                    print "freshStart... clean working directory ${env.JOB_NAME}"
                    sh 'ls -A1|xargs rm -rf' /* clean up our workspace */
                    sh 'rm -rf /home/jenkins/staging/*'
                }
            }

            
            def commitId

            def branchTemp
            //branch Name Parsing
            branchTemp = params.branchName
            branch=getBranchName(branchTemp)


            stage('Get Source') {
                git url: "https://gitlab.dspace.kt.co.kr/lostphone/lostphone-ui.git",
                    credentialsId: 'lostphone-git-credenitals',
                    branch: "${branch}"
                    commitId = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
            }
            def props = readProperties  file:'devops/jenkins/dockerize.properties'
            def tag = commitId
            def dockerRegistry = props['dockerRegistry']
            def image = props['image']
            def selector = props['selector']
            def namespace = props['namespace']
            def appname = props['appname']
            def apiKey = props['apiKey']
            def projectId = props['projectId']

            
            def yarnEnable = false  
            yarnEnable = params.yarnEnable
            
            /*
            stage("CodePrism RUN") {
                gl_CodePrismRunMD(apiKey, projectId)
            }
            */

            stage('npm build') {
                container('node') {
                    if( yarnEnable ){
                        sh "yarn config fix"
                        sh "yarn"
                        env.NODE_ENV = "production" 
                        sh "yarn build"
                    } else {
                        sh 'npm config fix'
                        sh 'npm install'
                        env.NODE_ENV = "development" 
                        sh "npm run build-dev"
                    }
                }
            }
            
            def unitTestEnable = true
            unitTestEnable = params.unitTestEnable
            
            if( unitTestEnable ){
                stage('unit test'){
                    container('node') {
                        sh "npm test"
                    }
                }
            }

            stage('Build Docker image') {
                container('build-tools') {
                    withCredentials([usernamePassword(credentialsId:'c02-okd4-cz-tb-registry-credentials',usernameVariable:'USERNAME',passwordVariable:'PASSWORD')]) {
                        sh "podman login -u ${USERNAME} -p ${PASSWORD} ${dockerRegistry} --tls-verify=false"
                        sh "podman build -t ${image}:${tag} -f devops/jenkins/Dockerfile . --tls-verify=false"
                        sh "podman push ${image}:${tag} --tls-verify=false"
                        sh "podman tag ${image}:${tag} ${image}:latest"
                        sh "podman push ${image}:latest --tls-verify=false"
                    }
                    // docker.withRegistry("${dockerRegistry}", 'c02-okd4-cz-tb-registry-credentials') {
                    //     sh "docker build -t ${image}:${tag} -f devops/jenkins/Dockerfile ."
                    //     sh "docker push ${image}:${tag}"
                    //     sh "docker tag ${image}:${tag} ${image}:latest"
                    //     sh "docker push ${image}:latest"
                    // }
                }
            }

            stage( 'Helm lint' ) {
                container('build-tools') {
                    dir('devops/helm/lostphone-ui'){
                        if ( isIpcRunningEnv ) {
                            sh """
                            # initial helm
                            # central helm repo can't connect
                            # setting stable repo by local repo
                            helm init --client-only --stable-repo-url "http://127.0.0.1:8879/charts" --skip-refresh
                            helm lint --namespace lostphone --tiller-namespace lostphone .
                            """
                        } else {
                            sh """
                            helm lint --namespace lostphone .
                            """
                        }
                  }
                }
            }

        } catch(e) {
            container('build-tools'){
                print "Clean up ${env.JOB_NAME} workspace..."
                sh 'ls -A1|xargs rm -rf' /* clean up our workspace */
            }


            currentBuild.result = "FAILED"
            print " **Error :: " + e.toString()+"**"
        } finally {
            EXIST=sh returnStatus: true, script: 'ls test-results.xml'
            if ( EXIST==0)
            {
                junit allowEmptyResults: true, testResults: 'test-results.xml'
            }
        }
    }
}
