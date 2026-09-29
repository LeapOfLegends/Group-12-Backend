pipeline {
    agent any

    environment {
        JAVA_HOME = '/usr/lib/jvm/java-25-amazon-corretto'
        PATH = "${JAVA_HOME}/bin:${env.PATH}"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout([$class: 'GitSCM',
                    branches: [[name: '*/main']],
                    userRemoteConfigs: [[url: 'https://github.com/LeapOfLegends/Group-12-Backend.git']]])
                sh 'git log --oneline -1'
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
                //sh 'mvn clean package -DskipTests'
                sh 'mvn test verify'
            }
        }

        stage('Test') {
            steps {
                sh 'mvn test'
            }
        }

        stage('Docker Build') {
            steps {
                sh 'docker build -t capstone-backend .'
            }
        }
    }

    post {
        always {
            junit testResults: '**/target/surefire-reports/*.xml',
                  allowEmptyResults: true
        }
    }
}
