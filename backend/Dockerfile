FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /out/api ./cmd/api && CGO_ENABLED=0 go build -o /out/vendor-sim ./cmd/vendor-sim

FROM alpine:3.22
COPY --from=build /out/api /usr/local/bin/api
COPY --from=build /out/vendor-sim /usr/local/bin/vendor-sim
# 自拍目錄：nobody 可寫；掛 named volume 時 Docker 會沿用這個目錄的擁有者。
RUN mkdir -p /data/selfies && chown nobody:nobody /data/selfies
EXPOSE 8080
USER nobody
ENTRYPOINT ["api"]
