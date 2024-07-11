def label = "demo1-${UUID.randomUUID().toString()}"

def isIpcRunningEnv = true
def isEpcRunningEnv = false

String getBranchName(branch) {
    branchTemp=sh returnStdout:true ,script:"""echo "$branch" |sed -E "s#origin/##g" """
    if(branchTemp){
        branchTemp=branchTemp.trim()
    }
    return branchTemp
}

podTemplate(cloud:'kubernetes',label: label, serviceAccount: 'default', namespace: 'demo1',
        containers: [
            containerTemplate(name: 'build-tools', image: 'ghcr.io/arsenalregistry/build-tools:v3.0', ttyEnabled: true, command: 'cat', privileged: true, alwaysPullImage: true),
            containerTemplate(name: 'jnlp', image: 'ghcr.io/arsenalregistry/inbound-agent:latest', args: '${computer.jnlpmac} ${computer.name}')
        ],
        volumes: [
                hostPathVolume(hostPath: '/etc/mycontainers', mountPath: '/var/lib/containers')
        ]
    ) {

    node(label){
        try {
            // freshStart
            def freshStart = params.freshStart

            if ( freshStart ) {
                container('build-tools'){
                    // remove previous working dir
                    print "freshStart... clean working directory ${env.JOB_NAME}"
                    sh 'ls -A1|xargs rm -rf' /* clean up our workspace */
                    sleep 1000000
                }
            }

            def commitId

            
            def branchTemp
            //branch Name Parsing
            branchTemp = params.branchName
            branch=getBranchName(branchTemp)

            stage('Get Source') {
                git url: "https://github.com/ArsenalRegistry/demo1.git",
                        credentialsId: 'github-credentials',
                        branch: "${branch}"
                commitId = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
            }


            def props = readProperties  file:'demo1-gitops-dev/backend-java/jenkins/dockerize.properties'
            def tag = commitId
            def dockerRegistry = props['dockerRegistry']
            def image = props['image']
            def namespace = props['namespace']

            def azureTenantId = props['azureTenantId']
            def acrName = props['acrName']
            
            // def unitTestEnable = true
            // unitTestEnable = params.unitTestEnable

            // def mvnSettings = "${env.WORKSPACE}/devops/jenkins/settings.xml"
            // if ( isIpcRunningEnv ) {
            //     mvnSettings = "${env.WORKSPACE}/devops/jenkins/settings.xml"
            // } else {
            //     mvnSettings = "${env.WORKSPACE}/devops/jenkins/settings-epc.xml"
            // }



            // def buildScope = params.buildScope

            // stage('dspace nexus setting update') {
            //     container('build-tools') {
            //         withCredentials([usernamePassword(credentialsId: 'partnership-nexus-credential', usernameVariable: 'nexusUsername', passwordVariable: 'nexusPassword')]) {
            //             sh "export NEXUS_USERNAME=${nexusUsername}"
            //             sh "export NEXUS_PASSWORD=${nexusPassword}"
            //             sh "sed -i 's/\${env.NEXUS_USERNAME}/${nexusUsername}/g' gradle.properties"
            //             sh "sed -i 's/\${env.NEXUS_PASSWORD}/${nexusPassword}/g' gradle.properties"
            //         }
            //     }
            // }



            stage('Gradle build &Unit Test') {
                container('build-tools') {
                    dir('backend-java'){
                        // sh 'gradle build -Dgradle.wrapperUser=${NEXUS_ID} -Dgradle.wrapperPassword=${NEXUS_PASSWORD}' // 프로젝트 빌드
                        sh 'gradle build' // 프로젝트 빌드
                        sh 'ls -alh'
                        sh 'ls ./build/libs -alh'
                    }
                }
            }


            stage('Build Docker image') {
                container('build-tools') {
                    withCredentials([usernamePassword(credentialsId: 'azure-credential', usernameVariable: 'AZURE_USERNAME', passwordVariable: 'AZURE_PASSWORD')]) {
                        // 로그인
                        sh "az login --username ${AZURE_USERNAME} --password ${AZURE_PASSWORD}"
                        // sh "az login --service-principal -u ${AZURE_CLIENT_ID} -p ${AZURE_CLIENT_SECRET} --tenant ${azureTenantId}"
                        sh "az acr login --name ${acrName}"
                        // 이미지 빌드/푸시
                        sh "docker build -t ${acrName}.azurecr.io/${image}:${tag} --build-arg sourceFile=`find target -name '*.jar' | head -n 1` -f demo1-gitops-dev/backend-java/jenkins/Dockerfile . --tls-verify=false"
                        sh "docker push ${acrName}.azurecr.io/${image}:${tag}"
                        sh "docker tag ${acrName}.azurecr.io/${image}:${tag} ${acrName}.azurecr.io/${image}:latest"
                        sh "docker push ${acrName}.azurecr.io/${image}:latest"
                        sleep 100000
                    }
                }
            }


            //  stage('Build Docker image') {
            //      container('build-tools') {
            //          withCredentials([usernamePassword(credentialsId:'c02-okd4-cz-tb-registry-credentials',usernameVariable:'USERNAME',passwordVariable:'PASSWORD')]) {
            //              sh "podman login -u ${USERNAME} -p ${PASSWORD} ${dockerRegistry}  --tls-verify=false"
            //              sh "podman build -t ${image}:${tag} --build-arg sourceFile=`find target -name '*.jar' | head -n 1` -f devops/jenkins/Dockerfile . --tls-verify=false"
            //              sh "podman push ${image}:${tag} --tls-verify=false"
            //              sh "podman tag ${image}:${tag} ${image}:latest"
            //              sh "podman push ${image}:latest --tls-verify=false"
            //          }
            //      }
            //  }

            stage( 'Helm lint' ) {
                container('build-tools') {
                    dir('devops/helm/partnership-api'){
                        if ( isIpcRunningEnv ) {
                            sh """
                            # initial helm
                            # central helm repo can't connect
                            # setting stable repo by local repo
                            helm init --client-only --stable-repo-url "http://127.0.0.1:8879/charts" --skip-refresh
                            helm lint --namespace demo1 --tiller-namespace demo1 .
                            """
                        } else {
                            sh """
                            helm lint --namespace demo1 .
                            """
                        }
                    }
                }
            }
        }
        catch(e) {
            container('build-tools'){
                print "Clean up ${env.JOB_NAME} workspace..."
                sh 'ls -A1|xargs rm -rf' /* clean up our workspace */
            }


            currentBuild.result = "FAILED"
            print " **Error :: " + e.toString()+"**"
        }
    }
}
