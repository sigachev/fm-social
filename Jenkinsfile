pipeline {
    agent any

    environment {
        DEPLOYMENT_NAME = "social"

        DOCKER_IMAGE             = "k8s-${DEPLOYMENT_NAME}"
        K8S_NAMESPACE            = "dev"
        KUBECONFIG_CREDENTIALS_ID = 'k8s-creds'

        NEXUS_CREDENTIALS_ID = "nexus-user-credentials"
        REPOSITORY_URI       = "10.0.0.70:8090"
        NEXUS_USER           = "jenkins"
        NEXUS_PWD            = "int68593"
    }

    stages {
        stage('Checkout') {
            steps {
                script {
                    checkout scm
                }
            }
        }

        stage('Build and Test') {
            steps {
                script {
                    sh "mvn clean package"
                }
            }
        }

        stage('Build Docker Image') {
            steps {
                script {
                    sh "docker build -t ${DOCKER_IMAGE} ."
                    sh "docker tag ${DOCKER_IMAGE}:latest ${REPOSITORY_URI}/${DOCKER_IMAGE}:latest"
                }
            }
        }

        stage('Upload to Nexus') {
            steps {
                script {
                    sh "docker login -u ${NEXUS_USER} -p ${NEXUS_PWD} ${REPOSITORY_URI}"
                    sh "docker push ${REPOSITORY_URI}/${DOCKER_IMAGE}"
                    sh "docker rmi ${DOCKER_IMAGE}"
                    sh "docker logout ${REPOSITORY_URI}"
                }
            }
        }

        stage('Deploy to Kubernetes') {
            steps {
                script {
                    sh 'pwd'
                    sh 'ls'
                    sh 'kubectl apply -f deployment.yaml -n dev'
                    sh "kubectl rollout restart deployment/${env.DEPLOYMENT_NAME} -n dev"
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }
        success {
            echo 'Build and deployment successful!'
        }
        failure {
            echo 'Build or deployment failed.'
        }
    }
}
