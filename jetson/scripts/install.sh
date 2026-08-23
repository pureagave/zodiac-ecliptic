#!/usr/bin/env bash
# Provision zvision on the Jetson as an auto-starting systemd service.
# Run from the repo:  sudo jetson/scripts/install.sh
set -euo pipefail

DEST=/opt/zodiac/jetson
SRC="$(cd "$(dirname "$0")/.." && pwd)"

# The documented install (DEPLOY.md §3) is a git clone directly at /opt/zodiac,
# in which case this script is already running from ${DEST} and there is
# nothing to copy — cp aborts on "identical" paths, and with set -e that killed
# the whole install before the service unit was ever written. Only copy when
# provisioning from a checkout somewhere else.
if [[ "${SRC}" != "${DEST}" ]]; then
  echo ">> installing zvision + zdeck from ${SRC} to ${DEST}"
  sudo mkdir -p "${DEST}"
  sudo cp -r "${SRC}/zvision" "${DEST}/"
  # zdeck rides along so an install-from-elsewhere box has the deck package that
  # the separately-installed zodiac-deck.service runs as `-m zdeck` from ${DEST}
  # (DECK.md). Its venv + StreamDeck/Pillow deps are still DECK.md's job — this
  # only lands the source, closing the ModuleNotFoundError gap (DOC-4).
  sudo cp -r "${SRC}/zdeck" "${DEST}/"
else
  echo ">> running from ${DEST} — service will use this checkout directly"
fi

# Default runtime args — edit here to switch source (fake|thermal|rgb), rate,
# FOV, or pin the vehicle-network NIC with --iface-ip.
if [[ ! -f /etc/default/zvision ]]; then
  echo ">> writing default config /etc/default/zvision"
  sudo tee /etc/default/zvision >/dev/null <<'EOF'
# Arguments passed to `python3 -m zvision`. See jetson/DEPLOY.md.
# Start with fake to prove the bus + HUD, then switch to the thermal. The
# thermal is serial-pinned to a stable /dev/zodiac-thermal by the udev rule
# below, so it works on ANY USB port:
#   ZVISION_ARGS=--hz 8 --camera thermal:/dev/zodiac-thermal:az=0:fov=160:lens=fisheye:fovref=d:name=thermal
#
# For a robust rig, list EVERY camera it might have: a camera that won't open is
# skipped (its arc reads BLIND on ZCOVER) and only ZERO-opened exits, so a lost
# camera never takes the others down. Pin identity-blind RGBs (Arducams all report
# serial SN0001) by /dev/v4l/by-path — NOT by-id; force fourcc=MJPG (YUYV is listed
# first and caps at 640x480); cap two RGBs at 1280x720 on the shared 480 Mbps bus
# (1080p may -ENOSPC). az/fov are per-camera and must be CALIBRATED against the
# vehicle nose once the cameras are mounted (fov=85 below is a vendor figure):
#   ZVISION_ARGS=--hz 8 --camera thermal:/dev/zodiac-thermal:az=0:fov=160:lens=fisheye:fovref=d:name=thermal --camera rgb:/dev/v4l/by-path/platform-3610000.usb-usb-0:2.1:1.0-video-index0:az=0:fov=85:fovref=h:lens=rectilinear:fourcc=MJPG:width=1280:height=720:name=rgb-front
ZVISION_ARGS=--source fake --hz 10
EOF
else
  echo ">> keeping existing /etc/default/zvision"
fi

echo ">> installing zodiac udev rules (device identity, port-independent)"
# Serial-pin the thermal to /dev/zodiac-thermal and give the Stream Deck its
# stable node — both survive a re-plug into any USB port. (The identity-blind
# Arducam RGB ring stays port-pinned via /dev/v4l/by-path; it has no rule here.)
sudo cp "${SRC}/systemd/70-zodiac-thermal.rules"   /etc/udev/rules.d/
sudo cp "${SRC}/systemd/70-zodiac-streamdeck.rules" /etc/udev/rules.d/
sudo udevadm control --reload-rules
sudo udevadm trigger --subsystem-match=video4linux --action=add

echo ">> installing systemd unit"
sudo cp "${SRC}/systemd/zvision.service" /etc/systemd/system/zvision.service
sudo systemctl daemon-reload
sudo systemctl enable zvision.service
sudo systemctl restart zvision.service

echo ">> done. Live logs:  journalctl -u zvision -f"
