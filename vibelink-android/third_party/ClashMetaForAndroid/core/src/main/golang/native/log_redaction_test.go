package main

import (
	"strings"
	"testing"
)

func TestNativeSinksRedactCredentialsButOnlyAppBridgeKeepsAuthorizationCapability(t *testing.T) {
	key := "tskey-" + "auth-test-only-not-a-real-credential"
	url := "https://login.tailscale.com/a/0123456789abcdef"
	text := "[Tailscale] registration " + key + " failed; visit " + url
	bridge := redactCredentials(text)
	if strings.Contains(bridge, key) || !strings.Contains(bridge, url) {
		t.Fatal("bridge credential/capability boundary failed")
	}
	platform := platformLogPayload(text)
	if strings.Contains(platform, key) || strings.Contains(platform, url) {
		t.Fatal("platform log leaked credential or capability")
	}
	if platformLogPayload("ordinary message") != "ordinary message" {
		t.Fatal("ordinary message changed")
	}
}
