// O11yLite Docker Bake configuration
//
// Local build:  docker buildx bake dev --load
// Production:   docker buildx bake
// Custom tag:   docker buildx bake --set "*.tags=myrepo/o11ylite:v1.0.0"

variable "REGISTRY" {
  default = "ghcr.io"
}

variable "IMAGE_NAME" {
  default = "zhming0/o11ylite"
}

variable "VERSION" {
  default = "latest"
}

// Local development build - single platform, loads to docker daemon
target "dev" {
  context = "."
  dockerfile = "Dockerfile"
  tags = ["o11ylite:dev"]
}

// Production multi-platform build
target "default" {
  context = "."
  dockerfile = "Dockerfile"
  tags = [
    "${REGISTRY}/${IMAGE_NAME}:${VERSION}",
    "${REGISTRY}/${IMAGE_NAME}:latest"
  ]
  platforms = ["linux/amd64", "linux/arm64"]
}
