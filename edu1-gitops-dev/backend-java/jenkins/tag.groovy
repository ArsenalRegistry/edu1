def label = "Partnership-${UUID.randomUUID().toString()}" // namespace 이름을 기반으로 파드 템플릿에 할당할 레이블을 설정합니다.
def isIpcRunningEnv = true
def isEpcRunningEnv = false
def mattermostDevIncomingUrl//='http://10.220.185.200/hooks/r9mipirgjby5udxk481shjpdca'

String getBranchName(branch) { // 브랜치 이름에서 origin/을 제거하는 역할의 함수
    branchTemp=sh returnStdout:true ,script:"""echo "$branch" |sed -E "s#origin/##g" """
    if(branchTemp){
        branchTemp=branchTemp.trim()
    }
    return branchTemp
}
// 파이프라인에서 사용할 Kubernetes 파드 템플릿을 정의합니다
podTemplate(cloud:'c02-okd4-cz-tb',label: label, serviceAccount: 'default', namespace: 'partnership',
        containers: [
                containerTemplate(name: 'build-tools', image: 'ktis-bastion01.container.ipc.kt.com:5000/alpine/build-tools:v3.0', ttyEnabled: true, command: 'cat', privileged: true, alwaysPullImage: true),
                containerTemplate(name: 'jnlp', image: 'ktis-bastion01.container.ipc.kt.com:5000/jenkins/jnlp-slave:alpine', args: '${computer.jnlpmac} ${computer.name}')
        ],
        volumes: [
                hostPathVolume(hostPath: '/etc/mycontainers', mountPath: '/var/lib/containers'),
                nfsVolume(mountPath: '/home/jenkins', serverAddress: '10.217.67.145', serverPath: '/data/nfs/devops/jenkins-slave-pv', readOnly: false)
        ]
        ) {

    node(label) {
        if ( isIpcRunningEnv ) {
            library 'pipeline-lib'
        }
        try {

            
            // freshStart 
            def freshStart = params.freshStart

            if ( freshStart ) { // freshStart 매개변수에 따라 작업 디렉토리를 초기화하는 등의 작업을 수행합니다
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
            milestone=params.version // jenkins 변수에서 설정한 version 값 
            def milestoneId

            stage('Get Source') { // 소스 코드를 가져오는 작업을 수행합니다
                git url: "https://gitlab.dspace.kt.co.kr/partnership/partnership-api.git",
                    credentialsId: 'partnership-git-credentials',
                    branch: "${branchName}"
            
            }

            def props = readProperties  file:'devops/jenkins/tag.properties'
            def dockerRegistry = props['dockerRegistry']
            def image = props['image']
            def gitProjectUrl = props['gitProjectUrl']


            stage('Check Milestone'){ // milestone이 존재하는지 확인합니다
                withCredentials([string(credentialsId: 'partnership-git-token', variable: 'TOKEN')]){
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
        
            stage('Check Tag'){ // 특정 milestone 해당하는 태그가 이미 존재하는지 확인하고, 이미 존재한다면 파이프라인을 중단시키는 역할을 합니다.
                withCredentials([string(credentialsId: 'partnership-git-token', variable: 'TOKEN')]){
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

            stage('dspace nexus setting update') {
                container('build-tools') {
                    withCredentials([usernamePassword(credentialsId: 'partnership-nexus-credential', usernameVariable: 'nexusUsername', passwordVariable: 'nexusPassword')]) {
                        sh "export NEXUS_USERNAME=${nexusUsername}"
                        sh "export NEXUS_PASSWORD=${nexusPassword}"
                        sh "sed -i 's/\${env.NEXUS_USERNAME}/${nexusUsername}/g' gradle.properties"
                        sh "sed -i 's/\${env.NEXUS_PASSWORD}/${nexusPassword}/g' gradle.properties"
                    }
                }
            }
            // 프로젝트를 빌드하는 곳으로 gradle 빌드를 기준으로 작성합니다. 사전에 발급받은 Nexus 계정을 jenkins credential로 넣어두고 그 값을 가져옵니다.
            stage('Gradle build &Unit Test') {
                container('build-tools') {
                    sh 'gradle build -Dgradle.wrapperUser=${NEXUS_ID} -Dgradle.wrapperPassword=${NEXUS_PASSWORD}' // 프로젝트 빌드
                    sh 'ls -alh'
                    sh 'ls ./build/libs -alh'

                    // sh "chmod 755 ./gradlew"
                    // sh "./gradlew build -Pipc"

                }
            }
            //빌드한 jar파일 등 결과물을 image화 시킵니다. 
             stage('Build Docker image') {
                 container('build-tools') {
                     withCredentials([usernamePassword(credentialsId:'c02-okd4-cz-tb-registry-credentials',usernameVariable:'USERNAME',passwordVariable:'PASSWORD')]) {
                         sh "podman login -u ${USERNAME} -p ${PASSWORD} ${dockerRegistry}  --tls-verify=false"
                         sh "podman build -t ${image}:${milestone} --build-arg sourceFile=`find target -name '*.jar' | head -n 1` -f devops/jenkins/Dockerfile . --build-arg ENVIRONMENT=prd --tls-verify=false"
                         sh "podman push ${image}:${milestone} --tls-verify=false"
                         sh "podman rmi ${image}:${milestone}"
                 }
             }
            // milestone 버전의 태깅작업을 진행합니다
            stage('tagging Version') {

                try {
                    
                    withCredentials([
                        [$class: 'UsernamePasswordMultiBinding', credentialsId: 'partnership-git-credentials', usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_PASSWORD']
                        ]) {
                        sh("git config credential.username ${env.GIT_USERNAME}")
                        sh("git config credential.helper '!echo password=\$GIT_PASSWORD; echo'")
                        sh("GIT_ASKPASS=true git push origin --tags")
                    
                        sh """
                            sed -i "s/^version.*/version: ${milestone}/g" devops/helm/partnership-api/values.yaml

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
	    // milestone 을 종료합니다
	    stage('Milestone Close') {
                
                // delete release branch & milestone close.
                try {
                    withCredentials([string(credentialsId: 'partnership-git-token', variable: 'TOKEN')]){
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
            
            // stage('Summary') {
            //     if (mattermostDevIncomingUrl && isIpcRunningEnv ) {
            //         gl_SummaryMessageMD(mattermostDevIncomingUrl)
            //     }
            }
        } catch(e) {
            container('build-tools'){
                print "Clean up ${env.JOB_NAME} workspace..."
                sh 'ls -A1|xargs rm -rf' /* clean up our workspace */
            }

            // currentBuild.result = "FAILED"
            // if ( mattermostDevIncomingUrl && isIpcRunningEnv ) {
            //    def buildMessage="**Error "+ e.toString()+"**"
            //    gl_SummaryMessageMD(mattermostDevIncomingUrl, 'F', buildMessage)
            // } else {
            //     print " **Error :: " + e.toString()+"**"
            // }
        }
    }
}
