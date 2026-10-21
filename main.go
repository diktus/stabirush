package main

import (
	"context"
	"embed"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"wireguard-ui/internal/config"
	"wireguard-ui/internal/web"
	"wireguard-ui/internal/wireguard"
)

//go:embed web/static/*
var embeddedFiles embed.FS

func main() {
	portFlag := flag.String("port", "", "Web UI port (default 51821)")
	confFlag := flag.String("config", "/etc/wireguard/wg0.conf", "Path to wireguard wg0.conf")
	dataFlag := flag.String("data", "/home/wireguard-ui/data", "Data directory for metadata and settings")
	flag.Parse()

	log.Println("Starting Stabirush WireGuard Web UI...")

	// Load configuration
	cfg, err := config.LoadConfig(*dataFlag)
	if err != nil {
		log.Fatalf("Failed to initialize config: %v", err)
	}

	if *portFlag != "" {
		cfg.WebPort = *portFlag
	}
	if *confFlag != "" {
		cfg.WGConfigFile = *confFlag
	}

	// Initialize manager
	mgr, err := wireguard.InitManager(cfg)
	if err != nil {
		log.Fatalf("Failed to initialize wireguard manager: %v", err)
	}

	// Prepare static file system
	staticSubFS, err := fs.Sub(embeddedFiles, "web/static")
	if err != nil {
		log.Fatalf("Failed to load embedded static files: %v", err)
	}

	// Create web server
	server := web.NewServer(cfg, mgr, staticSubFS)

	addr := fmt.Sprintf("0.0.0.0:%s", cfg.WebPort)
	httpServer := &http.Server{
		Addr:         addr,
		Handler:      server.Handler(),
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	// Print banner
	fmt.Println("==========================================================")
	fmt.Println("  Stabirush WireGuard Management & TCP Bypass UI")
	fmt.Printf("  WireGuard Interface: %s\n", cfg.WGInterface)
	fmt.Printf("  Config File:         %s\n", cfg.WGConfigFile)
	fmt.Printf("  Server Public IP:    %s\n", cfg.ServerEndpoint)
	fmt.Printf("  Web UI Listening on: http://0.0.0.0:%s\n", cfg.WebPort)
	fmt.Println("==========================================================")

	// Graceful shutdown handling
	idleConnsClosed := make(chan struct{})
	go func() {
		sigint := make(chan os.Signal, 1)
		signal.Notify(sigint, os.Interrupt, syscall.SIGTERM)
		<-sigint

		log.Println("Shutting down WireGuard UI server...")
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := httpServer.Shutdown(ctx); err != nil {
			log.Printf("HTTP server shutdown error: %v", err)
		}
		close(idleConnsClosed)
	}()

	if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatalf("HTTP server failed: %v", err)
	}

	<-idleConnsClosed
	log.Println("Server gracefully stopped.")
}
