FROM golang:1.26.4-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY cmd ./cmd
COPY internal ./internal
RUN CGO_ENABLED=0 GOOS=linux go test ./... && \
    CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/trade-monitor ./cmd/monitor && \
    mkdir -p /out/data && chown 65532:65532 /out/data

FROM scratch
COPY --from=build /out/trade-monitor /trade-monitor
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build --chown=65532:65532 /out/data /app/data
USER 65532:65532
EXPOSE 8090
ENTRYPOINT ["/trade-monitor"]
