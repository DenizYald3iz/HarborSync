FROM golang:1.22-alpine AS build
WORKDIR /src
COPY go.mod ./
RUN go mod download
COPY . .
RUN go test ./... && go build -o /out/telemetry-service .

FROM alpine:3.20
WORKDIR /app
COPY --from=build /out/telemetry-service /app/telemetry-service
EXPOSE 8082
ENTRYPOINT ["/app/telemetry-service"]
