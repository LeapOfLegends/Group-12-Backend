pipeline {
    agent any

    environment {
        JAVA_HOME = '/usr/lib/jvm/java-25-amazon-corretto'
        PATH = "${JAVA_HOME}/bin:${env.PATH}"
    }

    stages {
        stage('Checkout') {
            steps {
                echo 'Repo checked out'
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
                sh 'mvn clean package -DskipTests'
            }
        }

        stage('Test') {
            steps {
                sh 'mvn test'
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
