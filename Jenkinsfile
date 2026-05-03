pipeline {
    agent any

    environment {
        DEPLOYMENT_NAME = "social"

        DOCKER_IMAGE             = "fm-social"
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

        stage('Build') {
            steps {
                script {
                    sh "./mvnw clean package -DskipTests"
                }
            }
        }

        // TODO: re-enable when test infrastructure is set up (post-Prompt-7)
        // stage('Test') {
        //     steps {
        //         script {
        //             sh "./mvnw test"
        //         }
        //     }
        // }

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
                    sh 'kubectl apply -f k8s/ -n dev'
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
