FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
ENV GOPROXY=goproxy.io
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /touch-backend .

FROM alpine:3.24
COPY --from=build /touch-backend /touch-backend
VOLUME ["/data"]
EXPOSE 8080
ENV TOUCH_LISTEN_ADDR=:8080 TOUCH_SQLITE_PATH=/data/touch.db
ENTRYPOINT ["/touch-backend"]
