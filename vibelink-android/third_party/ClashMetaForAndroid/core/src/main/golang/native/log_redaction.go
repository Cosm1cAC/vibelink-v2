package main

import "regexp"

var tailscaleCredential = regexp.MustCompile(`(?i)tskey-[a-z0-9_-]+`)
var tailscaleAuthorizationURL = regexp.MustCompile(`(?i)https://login\.tailscale\.com/a/[^\s<>"]+`)

// This applies before every native sink, including the restricted app bridge.
func redactCredentials(payload string) string {
	return tailscaleCredential.ReplaceAllString(payload, "[Tailscale credential hidden]")
}

// The app bridge retains the URL only for its in-memory authorization capability.
// System logcat has no need to receive that capability.
func platformLogPayload(payload string) string {
	return tailscaleAuthorizationURL.ReplaceAllString(redactCredentials(payload), "[Tailscale authorization link hidden]")
}
