// Biến toàn cục cấp Script (tránh bị Jenkins withEnv reset về null khi chuyển qua các stage)
def GLOBAL_APP_VERSION   = 'unknown'
def GLOBAL_ENV_LOWER     = 'dev'
def GLOBAL_REMOTE_DIR    = ''
def GLOBAL_COMMIT_AUTHOR = 'N/A'
def GLOBAL_COMMIT_MSG    = 'N/A'
def GLOBAL_JAVA_HOME     = ''

pipeline {
    agent any

    options {
        timeout(time: 30, unit: 'MINUTES')
        timestamps()
        ansiColor('xterm')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '5'))
    }

    tools {
        maven 'Maven3.9'
    }

    environment {
        SERVICE_NAME      = 'tr-budget-service'
        REMOTE_BASE_PATH  = '/opt/spring-app'
        SSH_CONFIG_NAME   = 'SSH-Sv112'

        TELEGRAM_TOKEN    = "8423245923:AAFHSTIpl6A5vsbkRbYDMqkPw73t5PKP34E"
        TELEGRAM_CHAT_ID  = "-1003054884531"

        MAVEN_SSL_OPTS    = "-Dmaven.wagon.http.ssl.insecure=true -Dmaven.wagon.http.ssl.allowall=true -Dmaven.wagon.http.ssl.ignore.validity.dates=true"
        MAVEN_COMMON_OPTS = "-B -T 1C -s settings.xml -Dmaven.javadoc.skip=true -Dmaven.source.skip=true -Dgpg.skip=true"
    }

    parameters {
        choice(name: 'DEPLOY_ENV',
               choices: ['dev', 'uat', 'prod', 'sct', 'stttt', 'sgtvt'],
               description: '🌍 Chọn môi trường để triển khai')
        booleanParam(name: 'USE_DOCKER',    defaultValue: false, description: '🐋 Triển khai bằng Docker')
        booleanParam(name: 'PUSH_TO_NEXUS', defaultValue: false, description: '🚀 Đẩy artifact lên Nexus')
        booleanParam(name: 'SKIP_TESTS',    defaultValue: true,  description: '🧪 Bỏ qua Unit Tests (Bỏ chọn nếu muốn chạy test)')
    }

    stages {
        stage('🚀 Initialize') {
            steps {
                script {
                    checkout scm
                    sh "rm -rf target *.jar"

                    def appVersion = sh(
                        script: "grep -m1 '<revision>' pom.xml | sed 's/.*<revision>//;s/<\\/revision>.*//' | tr -d '[:space:]'",
                        returnStdout: true
                    ).trim()
                    if (!appVersion) {
                        appVersion = sh(
                            script: "grep -A3 '<parent>' pom.xml | grep '<version>' | head -1 | sed 's/.*<version>//;s/<\\/version>.*//' | tr -d '[:space:]'",
                            returnStdout: true
                        ).trim() ?: 'unknown'
                    }

                    def envLower  = (params.DEPLOY_ENV ?: 'dev').toLowerCase()
                    def remoteDir = "${env.REMOTE_BASE_PATH}/${env.SERVICE_NAME}-${envLower}"

                    def commitAuthor = 'N/A'
                    def commitMsg    = 'N/A'
                    try {
                        commitAuthor = sh(script: 'git log -1 --pretty=format:"%an"', returnStdout: true).trim()
                        commitMsg    = sh(script: 'git log -1 --pretty=format:"%s"',  returnStdout: true).trim()
                    } catch (Exception e) {
                        echo "⚠️ Git info unavailable: ${e.message}"
                    }

                    def detectedJavaHome = sh(
                        script: '''
                            if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then echo "$JAVA_HOME"; exit 0; fi
                            JAVA_BIN=$(readlink -f $(which java 2>/dev/null) 2>/dev/null)
                            if [ -n "$JAVA_BIN" ]; then dirname $(dirname "$JAVA_BIN"); exit 0; fi
                            for dir in /usr/lib/jvm/java-25* /usr/local/openjdk-25 /opt/java/25 /opt/java/openjdk /usr/lib/jvm/java-21* /usr/local/openjdk-21 /opt/java/21; do
                                [ -x "$dir/bin/java" ] && echo "$dir" && exit 0
                            done
                            echo ""
                        ''',
                        returnStdout: true
                    ).trim()

                    if (!detectedJavaHome) {
                        def reason = "Không tìm thấy Java 25. Cài JDK 25 hoặc đặt JAVA_HOME trên server Jenkins."
                        currentBuild.description = "❌ ${reason}"
                        error(reason)
                    }

                    GLOBAL_APP_VERSION   = appVersion
                    GLOBAL_ENV_LOWER     = envLower
                    GLOBAL_REMOTE_DIR    = remoteDir
                    GLOBAL_COMMIT_AUTHOR = commitAuthor
                    GLOBAL_COMMIT_MSG    = commitMsg
                    GLOBAL_JAVA_HOME     = detectedJavaHome

                    echo "✅ Version=${GLOBAL_APP_VERSION} | Env=${GLOBAL_ENV_LOWER} | JAVA_HOME=${GLOBAL_JAVA_HOME}"
                    currentBuild.displayName = "#${BUILD_NUMBER} - ${GLOBAL_ENV_LOWER} - v${GLOBAL_APP_VERSION}"
                }
            }
        }

        stage('🛠️ Build & Package') {
            steps {
                script {
                    try {
                        echo "Building ${env.SERVICE_NAME} v${GLOBAL_APP_VERSION}..."
                        def skipTests = (params.SKIP_TESTS == null) ? true : params.SKIP_TESTS.toString().toBoolean()
                        def testFlag  = skipTests ? '-DskipTests=true -Dmaven.test.skip=true' : ''
                        echo "🧪 SKIP_TESTS=${skipTests} | Flags=${testFlag}"
                        sh "JAVA_HOME='${GLOBAL_JAVA_HOME}' mvn ${env.MAVEN_COMMON_OPTS} clean package ${testFlag} ${env.MAVEN_SSL_OPTS}"
                        sh "cp target/${env.SERVICE_NAME}.jar ./"
                    } catch (Exception e) {
                        def reason = "Lỗi Build Maven: ${e.message}"
                        currentBuild.description = "❌ ${reason}"
                        error(reason)
                    }
                }
            }
        }

        stage('📦 Push to Nexus') {
            when { expression { return params.PUSH_TO_NEXUS == true } }
            steps {
                script {
                    try {
                        echo "🚀 Uploading ${env.SERVICE_NAME} v${GLOBAL_APP_VERSION} to Nexus..."
                        sh "JAVA_HOME='${GLOBAL_JAVA_HOME}' mvn ${env.MAVEN_COMMON_OPTS} deploy -Dmaven.deploy.skip=false -DskipTests=true -Dmaven.test.skip=true ${env.MAVEN_SSL_OPTS}"
                    } catch (Exception e) {
                        def reason = "Lỗi Push Nexus: ${e.message}"
                        currentBuild.description = "❌ ${reason}"
                        error(reason)
                    }
                }
            }
        }

        stage('🚚 Deployment') {
            steps {
                script {
                    try {
                        def svcName   = env.SERVICE_NAME
                        def envLower  = GLOBAL_ENV_LOWER ?: (params.DEPLOY_ENV ?: 'dev').toLowerCase()
                        def remoteDir = GLOBAL_REMOTE_DIR ?: "${env.REMOTE_BASE_PATH}/${svcName}-${envLower}"
                        def appVer    = GLOBAL_APP_VERSION ?: 'unknown'

                        def sourceFiles = params.USE_DOCKER
                            ? "${svcName}.jar"
                            : "${svcName}.jar,${svcName}.sh"

                        def remoteRelDir = remoteDir.startsWith('/') ? remoteDir.substring(1) : remoteDir

                        def execCmd = params.USE_DOCKER ? """
                            echo "--- DOCKER BUILD & DEPLOY ---"
                            mkdir -p "${remoteDir}"
                            cd "${remoteDir}" || { echo "❌ Cannot cd: ${remoteDir}"; exit 1; }
                            printf "FROM eclipse-temurin:25-jre-alpine\\nWORKDIR /app\\nCOPY ${svcName}.jar app.jar\\nENV TZ=Asia/Ho_Chi_Minh\\nENV SPRING_PROFILES_ACTIVE=docker,${envLower}\\nENTRYPOINT [\\\"java\\\", \\\"-Xms128m\\\", \\\"-Xmx512m\\\", \\\"-XX:+UseContainerSupport\\\", \\\"-jar\\\", \\\"app.jar\\\"]\\n" > Dockerfile.deploy
                            IMAGE_NAME="tyran8x/${svcName}"
                            docker build -f Dockerfile.deploy -t \${IMAGE_NAME}:${envLower} -t \${IMAGE_NAME}:${appVer} .
                            docker ps -q --filter "publish=8306" | xargs -r docker stop || true
                            if [ -d "/opt/docker" ]; then
                                cd "/opt/docker" && IMAGE_TAG=${envLower} docker compose up -d --no-deps dev-planning
                            else
                                docker stop dev-planning-service || true && docker rm dev-planning-service || true
                                docker run -d --name dev-planning-service -p 8306:8306 \${IMAGE_NAME}:${envLower}
                            fi
                            SUCCESS=0
                            for i in \$(seq 1 30); do
                                curl -s http://localhost:8306/actuator/health | grep "UP" > /dev/null && SUCCESS=1 && break
                                echo -n "." && sleep 2
                            done
                            if [ \$SUCCESS -eq 0 ]; then
                                echo "❌ Health Check Timeout"
                                docker logs --tail 60 dev-planning-service 2>&1 || true
                                ERR_SNIPPET=\$(docker logs --tail 20 dev-planning-service 2>&1 | head -c 1500 || echo "Docker health check timeout")
                                MSG="[Jenkins] 🚨 DOCKER STARTUP ERROR: ${svcName} - ${envLower} (v${appVer})
------------------------------------------------------------
\${ERR_SNIPPET}
------------------------------------------------------------
URL: ${env.BUILD_URL}console"
                                curl -s -X POST "https://api.telegram.org/bot${env.TELEGRAM_TOKEN}/sendMessage" -d chat_id="${env.TELEGRAM_CHAT_ID}" --data-urlencode "text=\${MSG}" > /dev/null 2>&1 || true
                                exit 1
                            fi
                            docker image prune -f
                            echo "✅ DOCKER DEPLOYMENT SUCCESSFUL!"
                        """ : """
                            echo "--- SHELL DEPLOYMENT ---"
                            mkdir -p "${remoteDir}"
                            cd "${remoteDir}" || { echo "❌ Cannot cd: ${remoteDir}"; exit 1; }
                            if [ -f /opt/spring-app/.env ]; then set -a; . /opt/spring-app/.env; set +a; fi
                            if [ -f .env ]; then set -a; . ./.env; set +a; fi
                            unset SPRING_CONFIG_IMPORT
                            chmod +x "${svcName}.sh"
                            export SPRING_PROFILES_ACTIVE="${envLower}"
                            if ! ./"${svcName}.sh" restart; then
                                ERR_SNIPPET=\$(head -c 1500 "/opt/logs/${svcName}/last-startup-error.txt" 2>/dev/null || tail -n 20 "/opt/logs/${svcName}/console.log" 2>/dev/null | head -c 1500 || echo "Không đọc được file log")
                                MSG="[Jenkins] 🚨 STARTUP ERROR LOG: ${svcName} - ${envLower} (v${appVer})
------------------------------------------------------------
\${ERR_SNIPPET}
------------------------------------------------------------
URL: ${env.BUILD_URL}console"
                                curl -s -X POST "https://api.telegram.org/bot${env.TELEGRAM_TOKEN}/sendMessage" -d chat_id="${env.TELEGRAM_CHAT_ID}" --data-urlencode "text=\${MSG}" > /dev/null 2>&1 || true
                                exit 1
                            fi
                            echo "✅ SHELL DEPLOYMENT SUCCESSFUL!"
                        """

                        sshPublisher(publishers: [
                            sshPublisherDesc(
                                configName: env.SSH_CONFIG_NAME,
                                transfers: [
                                    sshTransfer(
                                        sourceFiles: sourceFiles,
                                        remoteDirectory: remoteRelDir,
                                        execCommand: execCmd
                                    )
                                ],
                                verbose: true,
                                failOnError: true
                            )
                        ])
                    } catch (Exception e) {
                        def reason = "Lỗi Deployment: ${e.message}"
                        currentBuild.description = "❌ ${reason}"
                        error(reason)
                    }
                }
            }
        }
    }

    post {
        success {
            script {
                def envLow = GLOBAL_ENV_LOWER ?: (params.DEPLOY_ENV?.toLowerCase() ?: 'N/A')
                def ver    = GLOBAL_APP_VERSION ?: 'N/A'
                def author = GLOBAL_COMMIT_AUTHOR ?: 'N/A'
                def msg    = GLOBAL_COMMIT_MSG ?: 'N/A'
                currentBuild.description = "✅ Deployed to ${envLow} v${ver}"
                sendNotification("SUCCESS ✅", ver, envLow, author, msg, "")
            }
        }
        failure {
            script {
                def envLow = GLOBAL_ENV_LOWER ?: (params.DEPLOY_ENV?.toLowerCase() ?: 'N/A')
                def ver    = GLOBAL_APP_VERSION ?: 'N/A'
                def author = GLOBAL_COMMIT_AUTHOR ?: 'N/A'
                def msg    = GLOBAL_COMMIT_MSG ?: 'N/A'
                def reason = currentBuild.description ?: 'Unknown Error'
                sendNotification("FAILED ❌", ver, envLow, author, msg, reason)
            }
        }
        cleanup {
            sh "rm -f *.jar"
        }
    }
}

def sendNotification(String status, String version, String envName, String author, String commit, String reason) {
    def svcName  = env.SERVICE_NAME
    def token    = env.TELEGRAM_TOKEN
    def chatId   = env.TELEGRAM_CHAT_ID
    def buildUrl = env.BUILD_URL

    def reasonRow = reason ? "REASON   : ${reason}\n" : ""
    def subject   = "[Jenkins] ${status}: ${svcName} - ${envName} (v${version})"
    def body = """\
        ${reasonRow}------------------------------------------------------------
        SERVICE  : ${svcName}
        VERSION  : ${version}
        ENV      : ${envName}
        DURATION : ${currentBuild.durationString}
        URL      : ${buildUrl}
        ------------------------------------------------------------
        COMMIT   : ${commit}
        AUTHOR   : ${author}
        ------------------------------------------------------------""".stripIndent()

    if (token && chatId) {
        try {
            def telegramMsg = "${subject}\n${body}"
            sh "curl -s -X POST https://api.telegram.org/bot${token}/sendMessage -d chat_id=${chatId} --data-urlencode \"text=${telegramMsg}\""
        } catch (Exception e) {
            echo "❌ Telegram notification failed: ${e.message}"
        }
    }
}
