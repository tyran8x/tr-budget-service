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
        SERVICE_NAME          = 'tr-budget-service'
        SERVICE_PORT          = '8503'
        COMPOSE_SERVICE_NAME  = 'dev-budget'
        CONTAINER_NAME        = 'budget-service'
        REMOTE_BASE_PATH      = '/opt/spring-app'
        SSH_CONFIG_NAME       = 'SSH-Sv112'
        TELEGRAM_TOKEN_CRED   = 'telegram-bot-token'
        TELEGRAM_CHAT_ID_CRED = 'telegram-chat-id'
        NEXUS_CRED_ID         = 'nexus-maven-credentials'

        MAVEN_COMMON_OPTS     = "-B -T 1C -s settings.xml -Dmaven.javadoc.skip=true -Dmaven.source.skip=true -Dgpg.skip=true"
    }

    parameters {
        choice(name: 'DEPLOY_ENV',
               choices: ['dev', 'uat', 'prod', 'sct', 'stttt', 'sgtvt', 'ict-dng', 'bacninh', 'danhgiacntt', 'hdnd', 'qlc'],
               description: '🌍 Chọn môi trường để triển khai')
        booleanParam(name: 'USE_DOCKER',    defaultValue: false, description: '🐋 Triển khai bằng Docker (Non-root Dockerfile)')
        booleanParam(name: 'PUSH_TO_NEXUS', defaultValue: false, description: '🚀 Đẩy artifact lên Nexus')
        booleanParam(name: 'SKIP_TESTS',    defaultValue: false, description: '🧪 Bỏ qua kiểm thử (Mặc định FALSE — bắt buộc chạy Unit/Security/Flyway Tests)')
    }

    stages {
        stage('🚀 Initialize') {
            steps {
                script {
                    checkout scm
                    sh 'rm -rf target *.jar'

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

                    echo "✅ Service=${env.SERVICE_NAME} | Port=${env.SERVICE_PORT} | Version=${GLOBAL_APP_VERSION} | Env=${GLOBAL_ENV_LOWER} | JAVA_HOME=${GLOBAL_JAVA_HOME}"
                    currentBuild.displayName = "#${BUILD_NUMBER} - ${GLOBAL_ENV_LOWER} - v${GLOBAL_APP_VERSION}"
                }
            }
        }

        stage('🔐 Gate G1: Secret & Security Hygiene') {
            steps {
                script {
                    sh '''
                        set -eu
                        echo "🔍 Checking repository for hardcoded secrets & insecure build flags..."
                        if command -v gitleaks >/dev/null 2>&1; then
                            gitleaks detect --source . --no-git --redact
                        fi
                        if grep -rnE '([0-9]{8,10}:AA[0-9A-Za-z_-]{30,}|ssl[.]insecure=true|[J]PA_DDL_AUTO:-update|[D]nictPro@123|3vHJBiq1727)' \
                            Jenkinsfile settings.xml Dockerfile tr-budget-service.sh src/main/resources 2>/dev/null; then
                            echo "❌ Security Gate G1 failed: secret or insecure runtime flag detected!"
                            exit 1
                        fi
                        if ! grep -q "USER appuser" Dockerfile; then
                            echo "❌ Security Gate G1 failed: Dockerfile must enforce non-root USER appuser!"
                            exit 1
                        fi
                        echo "✅ Gate G1 passed."
                    '''
                }
            }
        }

        stage('🧪 Gate G2: Unit, Security & Flyway Tests') {
            when {
                expression {
                    return !((params.SKIP_TESTS != null) && params.SKIP_TESTS.toString().toBoolean())
                }
            }
            steps {
                script {
                    try {
                        withEnv(["JAVA_HOME=${GLOBAL_JAVA_HOME}", "MAVEN_OPTS_RUN=${env.MAVEN_COMMON_OPTS}"]) {
                            sh '''
                                set -eu
                                mvn ${MAVEN_OPTS_RUN} test
                            '''
                        }
                    } catch (Exception e) {
                        def reason = "Lỗi Gate G2 (Unit/Security/Flyway Tests): ${e.message}"
                        currentBuild.description = "❌ ${reason}"
                        error(reason)
                    } finally {
                        junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml'
                    }
                }
            }
        }

        stage('🛠️ Build & Package') {
            steps {
                script {
                    try {
                        echo "Building ${env.SERVICE_NAME} v${GLOBAL_APP_VERSION}..."
                        withEnv([
                            "JAVA_HOME=${GLOBAL_JAVA_HOME}",
                            "MAVEN_OPTS_RUN=${env.MAVEN_COMMON_OPTS}",
                            "SVC_NAME=${env.SERVICE_NAME}"
                        ]) {
                            sh '''
                                set -eu
                                mvn ${MAVEN_OPTS_RUN} package -DskipTests=true
                                cp "target/${SVC_NAME}.jar" ./
                            '''
                        }
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
                        withCredentials([
                            usernamePassword(
                                credentialsId: env.NEXUS_CRED_ID,
                                usernameVariable: 'NEXUS_USERNAME',
                                passwordVariable: 'NEXUS_PASSWORD'
                            )
                        ]) {
                            withEnv(["JAVA_HOME=${GLOBAL_JAVA_HOME}", "MAVEN_OPTS_RUN=${env.MAVEN_COMMON_OPTS}"]) {
                                sh '''
                                    set -eu
                                    mvn ${MAVEN_OPTS_RUN} deploy -Dmaven.deploy.skip=false -DskipTests=true -Dmaven.test.skip=true
                                '''
                            }
                        }
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
                        def svcName       = env.SERVICE_NAME
                        def svcPort       = env.SERVICE_PORT
                        def composeSvc    = env.COMPOSE_SERVICE_NAME
                        def containerName = env.CONTAINER_NAME
                        def envLower      = GLOBAL_ENV_LOWER ?: (params.DEPLOY_ENV ?: 'dev').toLowerCase()
                        def remoteDir     = GLOBAL_REMOTE_DIR ?: "${env.REMOTE_BASE_PATH}/${svcName}-${envLower}"
                        def appVer        = GLOBAL_APP_VERSION ?: 'unknown'

                        def sourceFiles = params.USE_DOCKER
                            ? "${svcName}.jar,Dockerfile,pom.xml,settings.xml"
                            : "${svcName}.jar,${svcName}.sh"

                        def remoteRelDir = remoteDir.startsWith('/') ? remoteDir.substring(1) : remoteDir

                        def execCmd = params.USE_DOCKER ? """
                            set -eu
                            echo "--- DOCKER BUILD & DEPLOY (NON-ROOT Dockerfile) ---"
                            mkdir -p "${remoteDir}/target" "${remoteDir}/src"
                            cd "${remoteDir}" || { echo "❌ Cannot cd: ${remoteDir}"; exit 1; }
                            mv -f "${svcName}.jar" "target/${svcName}.jar"
                            IMAGE_NAME="tyran8x/${svcName}"
                            DOCKER_BUILDKIT=1 docker build -f Dockerfile --build-arg SKIP_TESTS=true -t "\${IMAGE_NAME}:${envLower}" -t "\${IMAGE_NAME}:${appVer}" .
                            CONTAINER_UID=\$(docker run --rm --entrypoint id "\${IMAGE_NAME}:${envLower}" -u)
                            if [ "\${CONTAINER_UID}" = "0" ]; then
                                echo "❌ Refusing to deploy container running as root (UID=0)"
                                exit 1
                            fi
                            docker ps -q --filter "publish=${svcPort}" | xargs -r docker stop || true
                            if [ -d "/opt/docker" ]; then
                                cd "/opt/docker" && IMAGE_TAG="${envLower}" docker compose up -d --no-deps ${composeSvc}
                            else
                                docker stop "${containerName}" "dev-${containerName}" || true
                                docker rm "${containerName}" "dev-${containerName}" || true
                                docker run -d --name "${envLower}-${containerName}" -p ${svcPort}:${svcPort} \
                                    -e SPRING_PROFILES_ACTIVE="docker,${envLower}" \
                                    -e JPA_DDL_AUTO="validate" \
                                    -e FLYWAY_ENABLED="true" \
                                    "\${IMAGE_NAME}:${envLower}"
                            fi
                            SUCCESS=0
                            for i in \$(seq 1 30); do
                                curl -sf http://localhost:${svcPort}/actuator/health | grep "UP" > /dev/null && SUCCESS=1 && break
                                echo -n "." && sleep 2
                            done
                            if [ \$SUCCESS -eq 0 ]; then
                                echo "❌ Health Check Timeout on port ${svcPort}"
                                docker logs --tail 60 "${envLower}-${containerName}" 2>&1 || true
                                exit 1
                            fi
                            docker image prune -f
                            echo "✅ DOCKER DEPLOYMENT SUCCESSFUL (UID=\${CONTAINER_UID}, port=${svcPort})!"
                        """ : """
                            set -eu
                            echo "--- SHELL DEPLOYMENT ---"
                            mkdir -p "${remoteDir}"
                            cd "${remoteDir}" || { echo "❌ Cannot cd: ${remoteDir}"; exit 1; }
                            if [ -f /opt/spring-app/.env ]; then set -a; . /opt/spring-app/.env; set +a; fi
                            if [ -f .env ]; then set -a; . ./.env; set +a; fi
                            unset SPRING_CONFIG_IMPORT
                            chmod +x "${svcName}.sh"
                            export SPRING_PROFILES_ACTIVE="${envLower}"
                            export SERVER_PORT_ENV="${svcPort}"
                            export JPA_DDL_AUTO="\${JPA_DDL_AUTO:-validate}"
                            if ! ./"${svcName}.sh" restart; then
                                echo "❌ Shell deployment failed — check /opt/logs/${svcName}/last-startup-error.txt"
                                exit 1
                            fi
                            echo "✅ SHELL DEPLOYMENT SUCCESSFUL (port=${svcPort})!"
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
            sh 'rm -f *.jar'
        }
    }
}

def sendNotification(String status, String version, String envName, String author, String commit, String reason) {
    def svcName  = env.SERVICE_NAME
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

    def telegramMsg = "${subject}\n${body}"
    try {
        withCredentials([
            string(credentialsId: env.TELEGRAM_TOKEN_CRED, variable: 'TG_TOKEN'),
            string(credentialsId: env.TELEGRAM_CHAT_ID_CRED, variable: 'TG_CHAT_ID')
        ]) {
            withEnv(["TG_MSG=${telegramMsg}"]) {
                sh '''
                    set +x
                    if [ -n "${TG_TOKEN:-}" ] && [ -n "${TG_CHAT_ID:-}" ]; then
                        curl -sS -X POST "https://api.telegram.org/bot${TG_TOKEN}/sendMessage" \
                            -d "chat_id=${TG_CHAT_ID}" \
                            --data-urlencode "text=${TG_MSG}" > /dev/null
                    fi
                '''
            }
        }
    } catch (Exception e) {
        echo "⚠️ Telegram notification skipped or failed: ${e.message}"
    }
}
