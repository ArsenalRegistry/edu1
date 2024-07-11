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
                }
            }

            def branchTemp
            //branch Name Parsing
            branchTemp = params.branchName            
            branchName=getBranchName(branchTemp)
            
            print "branchName = " + branchName

            def milestone
            milestone=params.version
            def milestoneId

            stage('Get Source') {

                sh """
                    git config --global user.email "jenkins@kt.com"
                    git config --global user.name "jenkins"
                    git config --global credential.helper cache
                    git config --global push.default simple
                """
                git url: "https://gitlab.dspace.kt.co.kr/lostphone/lostphone-UI.git",
                    credentialsId: 'lostphone-git-credenitals',
                    branch: "${branchName}"
            
            }

            /*
            // install and execute Sparrow
            stage('Sparrow') {
                container('sparrow') {
                    def sparrowProject = props['sparrowProject']
                    gl_SparrowRunMD(sparrowProject)
                }
            }
            */

            def props = readProperties  file:'devops/jenkins/tag.properties'
            def dockerRegistry = props['dockerRegistry']
            def image = props['image']
            def gitProjectUrl = props['gitProjectUrl']

            stage('Check Milestone'){
                withCredentials([string(credentialsId: 'lostphone-git-token', variable: 'TOKEN')]){            
                    container('build-tools'){
                        result = sh returnStdout:true, script: """curl --header PRIVATE-TOKEN:$TOKEN ${gitProjectUrl}/milestones?state=active | jq -r '.[] | select(.title == "${milestone}") | .id'"""
                        try {
                            result = result.toInteger()
                        } catch (e) {
                            result = 0
                        }
                        
                        if ( result == 0 ){
                           autoCancelled = true
                           print "Error MileStone(version) is not opend"
                           error('Error MileStone(version) is not opend')
                        }
                        
                        milestoneId = result
                     }
                 }
            }
        
            stage('Check Tag'){
                withCredentials([string(credentialsId: 'lostphone-git-token', variable: 'TOKEN')]){
                    container('build-tools'){
                        result = sh returnStdout:true, script: """curl --header PRIVATE-TOKEN:$TOKEN ${gitProjectUrl}/repository/tags | jq -r '.[].name' | grep -w '^${milestone}\$' | wc -l"""
                        result = result.toInteger()
                    
                        if ( result != 0 ){
                            autoCancelled = true
                            print "Error Already builded Tag is exist"
                            error('Error Already builded Tag is exist')
                        }
                     }
                }
            }

            def yarnEnable = false  
            yarnEnable = params.yarnEnable

            stage('npm build') {
                container('node') {
                    // need to setting project root .npmrc
                    if( yarnEnable ){
                        sh "yarn config fix"
                        sh "yarn"
                        env.NODE_ENV = "production" 
                        sh "yarn build"
                    } else {
                        sh 'npm config fix'
                        sh 'npm install'
                        env.NODE_ENV = "prd" 
                        sh "npm run build-prd"
                    }
                }
            }
            
            // stage('unit test'){
            //     container('node') {
            //         sh "npm test"
            //     }
            // }
            
            stage('Build Docker image') {
                container('build-tools') {
                    withCredentials([usernamePassword(credentialsId:'c02-okd4-cz-tb-registry-credentials',usernameVariable:'USERNAME',passwordVariable:'PASSWORD')]) {
                        sh "podman login -u ${USERNAME} -p ${PASSWORD} ${dockerRegistry}  --tls-verify=false"
                        sh "podman build -t ${image}:${milestone} --build-arg sourceFile=`find target -name '*.jar' | head -n 1` -f devops/jenkins/Dockerfile . --tls-verify=false"
                        sh "podman push ${image}:${milestone} --tls-verify=false"
                        sh "podman rmi ${image}:${milestone}"
                    }
                }
            }

            stage('tagging Version') {

                try {
                    
                    withCredentials([
                        [$class: 'UsernamePasswordMultiBinding', credentialsId: 'lostphone-git-credenitals', usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_PASSWORD']
                        ]) {
                        sh("git config credential.username ${env.GIT_USERNAME}")
                        sh("git config credential.helper '!echo password=\$GIT_PASSWORD; echo'")
                        sh("GIT_ASKPASS=true git push origin --tags")
                    
                        sh """
                            sed -i "s/^version.*/version: ${milestone}/g" devops/helm/lostphone-ui/values.yaml

                            git commit -am "helm version changed"
                            git tag -a ${milestone} -m "jenkins added"
                            git push --set-upstream origin ${branchName} 
                            git push --tags
                        """
                    }
                } finally {
                    sh("git config --unset credential.username")
                    sh("git config --unset credential.helper")
                }
                
            }
	    
	        stage('Milestone Close') {
                
                // delete release branch & milestone close.
                try {
                    withCredentials([string(credentialsId: 'lostphone-git-token', variable: 'TOKEN')]){
                        container('build-tools'){
                            sh("curl --header PRIVATE-TOKEN:$TOKEN --request DELETE ${gitProjectUrl}/repository/branches/${branchName}")
                            sh("curl --header PRIVATE-TOKEN:$TOKEN --request PUT    ${gitProjectUrl}/milestones/${milestoneId}?state_event=close")
                        }
                    }
                } catch(e) {
                    // continue if delete branch failed
                    print "Delete branch faild. please remove this release branch later."
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
