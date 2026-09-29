pipeline {
    agent any

    environment {
        JAVA_HOME = '/usr/lib/jvm/java-25-amazon-corretto'
        PATH = "${JAVA_HOME}/bin:${env.PATH}"
    }

    stages {
        stage('Checkout') {
    steps {
        checkout scm
        sh 'git log --oneline -1'
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
        sh 'docker build -t capstone-backend:${BUILD_NUMBER} .'
        sh 'docker tag capstone-backend:${BUILD_NUMBER} capstone-backend:latest'
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
                    docker run -d --name capstone-backend -p 8080:8080 capstone-backend:latest
                '''
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
