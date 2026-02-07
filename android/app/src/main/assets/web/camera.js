// Sentinel Browser Camera - camera.js

(function() {
    'use strict';

    const video = document.getElementById('videoElement');
    const canvas = document.getElementById('overlayCanvas');
    const ctx = canvas.getContext('2d');
    const statusEl = document.getElementById('camStatus');
    const detCountEl = document.getElementById('detCount');
    const procFpsEl = document.getElementById('procFps');

    let processing = false;
    let frameCount = 0;
    let lastFpsTime = Date.now();

    // Capture canvas for sending frames
    const captureCanvas = document.createElement('canvas');
    const captureCtx = captureCanvas.getContext('2d');

    async function initCamera() {
        try {
            const stream = await navigator.mediaDevices.getUserMedia({
                video: {
                    facingMode: 'environment',
                    width: { ideal: 640 },
                    height: { ideal: 480 }
                }
            });
            video.srcObject = stream;
            await video.play();

            // Set canvas sizes once video is playing
            video.addEventListener('loadedmetadata', () => {
                canvas.width = video.videoWidth;
                canvas.height = video.videoHeight;
                captureCanvas.width = video.videoWidth;
                captureCanvas.height = video.videoHeight;
            });

            statusEl.textContent = 'Active';
            startDetectionLoop();
        } catch (err) {
            statusEl.textContent = 'Camera Error';
            document.querySelector('.camera-container').innerHTML =
                '<div class="camera-error">' +
                    '<p>Could not access camera</p>' +
                    '<p style="font-size:12px;color:var(--text-muted)">' + err.message + '</p>' +
                '</div>';
        }
    }

    function startDetectionLoop() {
        setInterval(captureAndDetect, 500);
    }

    async function captureAndDetect() {
        if (processing || video.readyState < 2) return;
        processing = true;

        try {
            // Draw current frame to capture canvas
            captureCtx.drawImage(video, 0, 0);

            // Convert to JPEG blob
            const blob = await new Promise(resolve => {
                captureCanvas.toBlob(resolve, 'image/jpeg', 0.8);
            });

            if (!blob) { processing = false; return; }

            // Send to detection API
            const formData = new FormData();
            formData.append('image', blob, 'frame.jpg');

            const response = await fetch('/api/detect', {
                method: 'POST',
                body: formData
            });

            const result = await response.json();

            // Update stats
            frameCount++;
            const now = Date.now();
            if (now - lastFpsTime >= 1000) {
                procFpsEl.textContent = frameCount.toFixed(1);
                frameCount = 0;
                lastFpsTime = now;
            }

            // Draw detections
            drawDetections(result.detections || []);
            detCountEl.textContent = (result.detections || []).length;

        } catch (err) {
            // Silently handle network errors
        }

        processing = false;
    }

    function drawDetections(detections) {
        // Ensure canvas matches video
        if (canvas.width !== video.videoWidth) {
            canvas.width = video.videoWidth;
            canvas.height = video.videoHeight;
        }

        ctx.clearRect(0, 0, canvas.width, canvas.height);

        for (const det of detections) {
            const bbox = det.bbox;
            if (!bbox) continue;

            const x = bbox.left;
            const y = bbox.top;
            const w = bbox.right - bbox.left;
            const h = bbox.bottom - bbox.top;

            // Color by type
            const color = det.type === 'person' ? '#2196F3' : '#FF9800';

            // Bounding box
            ctx.strokeStyle = color;
            ctx.lineWidth = 2;
            ctx.strokeRect(x, y, w, h);

            // Label background
            const label = det.type + ' ' + (det.confidence * 100).toFixed(0) + '%';
            ctx.font = '12px sans-serif';
            const textWidth = ctx.measureText(label).width;
            ctx.fillStyle = color;
            ctx.fillRect(x, y - 18, textWidth + 8, 18);

            // Label text
            ctx.fillStyle = '#fff';
            ctx.fillText(label, x + 4, y - 4);

            // License plate
            if (det.licensePlate) {
                ctx.fillStyle = 'rgba(0,0,0,0.7)';
                ctx.fillRect(x, y + h, textWidth + 8, 18);
                ctx.fillStyle = '#fff';
                ctx.fillText(det.licensePlate, x + 4, y + h + 14);
            }
        }
    }

    initCamera();
})();
