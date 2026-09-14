FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod main.go protocol.go relay.go protocol_test.go ./
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /db-proxy .

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /db-proxy /db-proxy
# nonroot uid 65532; no shell, read-only rootfs at runtime (see compose).
USER 65532:65532
ENTRYPOINT ["/db-proxy"]
