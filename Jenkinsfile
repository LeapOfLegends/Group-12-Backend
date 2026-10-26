pipeline {
    agent any

    triggers {
        pollSCM('H/5 * * * *')
    }

    environment {
    JAVA_HOME = '/usr/lib/jvm/java-25-amazon-corretto'
    PATH = "${JAVA_HOME}/bin:${env.PATH}"
    KAFKA_BOOTSTRAP_SERVERS = '10.14.142.109:9092'
    GITHUB_REPO_URL = 'https://github.com/berribitz/Group-12-Backend.git'
    }
stages{
   stage('Checkout') {
    steps {
        checkout scm
        sh 'git log --oneline -1'

        script {
            env.GIT_COMMIT_SHA = sh(
                script: 'git rev-parse HEAD',
                returnStdout: true
            ).trim()

            echo "Building commit: ${env.GIT_COMMIT_SHA}"

            step([
                $class: 'GitHubCommitStatusSetter',

                reposSource: [
                    $class: 'ManuallyEnteredRepositorySource',
                    url: env.GITHUB_REPO_URL
                ],

                commitShaSource: [
                    $class: 'ManuallyEnteredShaSource',
                    sha: env.GIT_COMMIT_SHA
                ],

                contextSource: [
                    $class: 'ManuallyEnteredCommitContextSource',
                    context: 'Jenkins'
                ],

                statusResultSource: [
                    $class: 'ConditionalStatusResultSource',
                    results: [[
                        $class: 'AnyBuildResult',
                        state: 'PENDING',
                        message: 'Jenkins build is running'
                    ]]
                ]
            ])
        }
    }
}
        

        stage('Sanity check') {
            steps {
                echo 'Jenkins is running'
                sh 'echo "Hello from Jenkins"'
                sh 'pwd'
                sh 'ls -la'
                sh 'java -version'
                sh 'mvn -version'
            }
        }

        stage('Build') {
            steps {
                sh 'mvn clean package '
            }
        }

        stage('Docker Build') {
    steps {
        sh 'docker build -t capstone-backend:${BUILD_NUMBER} .'
        sh 'docker tag capstone-backend:${BUILD_NUMBER} capstone-backend:1.3.0'
        sh 'docker build -t capstone-etl:${BUILD_NUMBER} ETL-Pipeline'
        }
    }
    stage('Deploy to VM') {
            when {
                branch 'main'
            }
            steps {
                withCredentials([file(credentialsId: 'group-12-backend-env', variable: 'BACKEND_ENV_FILE')]) {
                    sh '''
                        docker network inspect capstone-network >/dev/null 2>&1 || docker network create capstone-network
                        docker rm -f capstone-backend 2>/dev/null || true
                        docker run -d --name capstone-backend --network capstone-network --env-file "$BACKEND_ENV_FILE" --restart unless-stopped -p 8081:8081 capstone-backend:${BUILD_NUMBER}
                    '''
                }
                withCredentials([file(credentialsId: 'group-12-etl-env', variable: 'ETL_ENV_FILE')]) {
                    sh '''
                        docker rm -f capstone-etl 2>/dev/null || true
                        docker run -d --name capstone-etl --network capstone-network --env-file "$ETL_ENV_FILE" --restart unless-stopped capstone-etl:${BUILD_NUMBER}
                    '''
                }
            }
            
        }
    }

    post {

    success {
        script {
            step([
                $class: 'GitHubCommitStatusSetter',

                reposSource: [
                    $class: 'ManuallyEnteredRepositorySource',
                    url: env.GITHUB_REPO_URL
                ],

                commitShaSource: [
                    $class: 'ManuallyEnteredShaSource',
                    sha: env.GIT_COMMIT_SHA
                ],

                contextSource: [
                    $class: 'ManuallyEnteredCommitContextSource',
                    context: 'Jenkins'
                ],

                statusResultSource: [
                    $class: 'ConditionalStatusResultSource',
                    results: [[
                        $class: 'AnyBuildResult',
                        state: 'SUCCESS',
                        message: 'Jenkins build passed'
                    ]]
                ]
            ])
        }
    }

    failure {
        script {
            step([
                $class: 'GitHubCommitStatusSetter',

                reposSource: [
                    $class: 'ManuallyEnteredRepositorySource',
                    url: env.GITHUB_REPO_URL
                ],

                commitShaSource: [
                    $class: 'ManuallyEnteredShaSource',
                    sha: env.GIT_COMMIT_SHA
                ],

                contextSource: [
                    $class: 'ManuallyEnteredCommitContextSource',
                    context: 'Jenkins'
                ],

                statusResultSource: [
                    $class: 'ConditionalStatusResultSource',
                    results: [[
                        $class: 'AnyBuildResult',
                        state: 'FAILURE',
                        message: 'Jenkins build failed'
                    ]]
                ]
            ])
        }
    }

    always {
        junit testResults: '**/target/surefire-reports/*.xml',
              allowEmptyResults: true
    }
}
}
