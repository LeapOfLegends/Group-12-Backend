pipeline {
    agent any

    environment {
    JAVA_HOME = '/usr/lib/jvm/java-25-amazon-corretto'
    PATH = "${JAVA_HOME}/bin:${env.PATH}"

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
                sh 'mvn clean package'
            }
        }

        stage('Docker Build') {
    steps {
        sh 'docker build -t capstone-backend:${BUILD_NUMBER} .'
        sh 'docker tag capstone-backend:${BUILD_NUMBER} capstone-backend:0.1.0'
        }
    }
    stage('Deploy to VM') {
            when {
                branch 'main'
            }
            steps {
                sh '''
                    docker stop capstone-backend || true
                    docker rm capstone-backend || true
                    docker run -d --name capstone-backend -p 8081:8081 capstone-backend:0.1.0
                '''
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
