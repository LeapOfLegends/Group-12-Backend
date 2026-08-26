pipeline {
    agent any

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
            }
        }

        stage('Build') {
            steps {
                echo 'No backend source yet; this is a smoke-test build.'
            }
        }

        stage('Test') {
            steps {
                echo 'No tests configured yet; build succeeds.'
            }
        }
    }
}
