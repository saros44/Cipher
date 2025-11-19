let overlayChart = null;
let latestOriginalHistogram = null;
const stagingCanvas = document.createElement('canvas');

// Block positions (must match encoder/decoder). Each entry is positions for a block permutation.
const BLOCK_POSITIONS = [
    [0, 3, 5, 12, 15, 7, 2, 10],
    [1, 4, 6, 13, 14, 8, 9, 11],
    [2, 5, 7, 0, 12, 6, 1, 14],
    [3, 6, 0, 9, 15, 2, 4, 11]
];

// Calculate how many plaintext characters can be encoded in an image (parity-per-block scheme)
function calculateMaxMessageLength(width, height) {
    if (!width || !height) return 0;
    const availableHeight = Math.max(0, height - 1); // reserve first row for AES key
    if (availableHeight <= 0) return 0;

    const blocksX = Math.ceil(width / 8);
    const blocksY = Math.ceil(availableHeight / 8);

    let capacityBits = 0; // one bit per block that has >=1 carrier
    for (let by = 0; by < blocksY; by++) {
        for (let bx = 0; bx < blocksX; bx++) {
            const startX = bx * 8;
            const blockW = Math.min(8, width - startX);
            const blockH = Math.min(8, availableHeight - by * 8);
            if (blockW <= 0 || blockH <= 0) continue;

            const rowIdx = (bx + by) % BLOCK_POSITIONS.length;
            const positions = BLOCK_POSITIONS[rowIdx];

            let hasValid = false;
            for (const p of positions) {
                const localX = p % 8;
                const localY = Math.floor(p / 8);
                if (localX >= blockW || localY >= blockH) continue;
                hasValid = true;
                break;
            }
            if (hasValid) capacityBits++;
        }
    }

    const capacityBytes = Math.floor(capacityBits / 8);
    // reserve one byte for null terminator used by encoder
    return Math.max(0, capacityBytes - 1);
}

function generateEncodedHistogram(imageBlob) {
    const histogramSection = document.querySelector('.histogram-section');
    const encodedImage = new Image();
    encodedImage.src = URL.createObjectURL(imageBlob);

    encodedImage.onload = function () {
        // compute encoded histogram into a temporary canvas (normalized percentages)
        const tempCanvas = document.createElement('canvas');
        const encodedHistogram = getImageHistogram(encodedImage, tempCanvas, { normalize: true, maxDim: 1024 });

        // Render simple RGB histogram for the encoded image
        renderSimpleHistogram(encodedHistogram);

        // Show the histogram section
        histogramSection.style.display = 'block';
    };
}

// Replace getImageHistogram to support normalization and downsampling
function getImageHistogram(imageElement, canvas, options = {}) {
    // options: { normalize: true/false, maxDim: number }
    const normalize = options.normalize !== undefined ? options.normalize : true;
    const maxDim = options.maxDim || 1024;

    const ctx = canvas.getContext('2d');

    // compute target size (downscale if image is large)
    const naturalW = imageElement.naturalWidth || imageElement.width || 1;
    const naturalH = imageElement.naturalHeight || imageElement.height || 1;
    let targetW, targetH;
    const scale = Math.min(1, maxDim / Math.max(naturalW, naturalH));
    targetW = Math.max(1, Math.floor(naturalW * scale));
    targetH = Math.max(1, Math.floor(naturalH * scale));

    canvas.width = targetW;
    canvas.height = targetH;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    // draw scaled image to canvas for sampling
    ctx.drawImage(imageElement, 0, 0, targetW, targetH);

    const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
    const data = imageData.data;

    // Initialize arrays for RGB channels
    const histogram = {
        red: new Array(256).fill(0),
        green: new Array(256).fill(0),
        blue: new Array(256).fill(0),
        totalPixels: (canvas.width * canvas.height)
    };

    // Iterate over pixel data
    for (let i = 0; i < data.length; i += 4) {
        histogram.red[data[i]]++;
        histogram.green[data[i + 1]]++;
        histogram.blue[data[i + 2]]++;
    }

    if (normalize && histogram.totalPixels > 0) {
        const denom = histogram.totalPixels;
        // convert to percentage (0..100)
        for (let i = 0; i < 256; i++) {
            histogram.red[i] = (histogram.red[i] / denom) * 100;
            histogram.green[i] = (histogram.green[i] / denom) * 100;
            histogram.blue[i] = (histogram.blue[i] / denom) * 100;
        }
    }

    return histogram;
}

// New: render a simple normalized RGB histogram (encoded image only)
function renderSimpleHistogram(hist) {
    const ctx = document.getElementById('histogram-overlay').getContext('2d');
    const labels = Array.from({ length: 256 }, (_, i) => i);

    const datasets = [
        {
            label: 'Red',
            data: hist.red,
            borderColor: 'rgb(211,28,66)',
            backgroundColor: 'rgba(211,28,66,0.08)',
            borderWidth: 2,
            fill: false,
            tension: 0.05
        },
        {
            label: 'Green',
            data: hist.green,
            borderColor: 'rgb(43,194,40)',
            backgroundColor: 'rgba(43,194,40,0.08)',
            borderWidth: 2,
            fill: false,
            tension: 0.05
        },
        {
            label: 'Blue',
            data: hist.blue,
            borderColor: 'rgb(34,55,189)',
            backgroundColor: 'rgba(34,55,189,0.08)',
            borderWidth: 2,
            fill: false,
            tension: 0.05
        }
    ];

    if (overlayChart) overlayChart.destroy();

    overlayChart = new Chart(ctx, {
        type: 'line',
        data: {
            labels,
            datasets
        },
        options: {
            responsive: true,
            plugins: {
                title: {
                    display: true,
                    text: ''
                },
                tooltip: {
                    mode: 'index',
                    intersect: false,
                    callbacks: {
                        label: function (context) {
                            const v = context.raw;
                            return context.dataset.label + ': ' + v.toFixed(4) + '%';
                        }
                    }
                }
            },
            interaction: {
                mode: 'index',
                intersect: false
            },
            scales: {
                x: {
                    title: { display: true, text: 'Pixel Intensity (0-255)' }
                },
                y: {
                    title: { display: true, text: 'Percentage of pixels (%)' }
                }
            }
        }
    });
}

// Replace previous display function: show remaining characters (max - currentLength)
function updateRemainingDisplay(maxChars, currentLength) {
    const maxLengthElement = document.getElementById('max-length');
    const textarea = document.getElementById('secret-message');

    if (!maxLengthElement) return;

    const safeMax = Number.isFinite(maxChars) ? Math.max(0, Math.floor(maxChars)) : 0;
    const curLen = Number.isInteger(currentLength) ? Math.max(0, currentLength) : (textarea ? textarea.value.length : 0);

    if (safeMax <= 0) {
        // No image selected or no capacity info available
        maxLengthElement.textContent = 'characters: select an image';
        maxLengthElement.removeAttribute('data-max-chars');
        if (textarea) textarea.removeAttribute('maxlength');
        return;
    }

    const remaining = Math.max(0, safeMax - curLen);
    // Show label + remaining number (e.g. "characters: 123")
    maxLengthElement.textContent = 'characters: ' + String(remaining);
    maxLengthElement.dataset.maxChars = String(safeMax);

    // Ensure the textarea maxlength is kept in sync
    if (textarea) textarea.maxLength = safeMax;
}

// Update input event: decrease displayed number as characters are added
document.getElementById('secret-message').addEventListener('input', function () {
    const dataAttr = document.getElementById('max-length').dataset.maxChars;
    const maxChars = dataAttr ? parseInt(dataAttr, 10) : null; // null means no limit known yet

    if (maxChars && Number.isFinite(maxChars)) {
        if (this.value.length > maxChars) {
            this.value = this.value.slice(0, maxChars);
        }
        updateRemainingDisplay(maxChars, this.value.length);
    } else {
        // No limit known: show prompt
        updateRemainingDisplay(0, this.value.length);
    }
});

document.getElementById('image-upload').addEventListener('change', function (event) {
    const file = event.target.files[0];
    const imgElement = document.getElementById('uploaded-image');

    if (file) {
        const reader = new FileReader();
        reader.onload = function (e) {
            imgElement.src = e.target.result;
            imgElement.classList.add('active');

            const img = new Image();
            img.onload = function () {
                const maxChars = calculateMaxMessageLength(img.width, img.height);
                // Set remaining considering current textarea value
                const current = document.getElementById('secret-message').value.length || 0;
                updateRemainingDisplay(maxChars, current);
                document.getElementById('secret-message').maxLength = maxChars;

                // compute and store original histogram
                latestOriginalHistogram = getImageHistogram(img, stagingCanvas);
            };
            img.src = e.target.result;
        };
        reader.readAsDataURL(file);
    } else {
        imgElement.src = '/icons/select.png';
        imgElement.classList.remove('active');
        updateRemainingDisplay(0, 0);
        latestOriginalHistogram = null;
    }
});

document.addEventListener('DOMContentLoaded', function () {
    const imgElement = document.getElementById('uploaded-image');
    imgElement.src = '/icons/select.png';
    imgElement.classList.remove('active');
    updateRemainingDisplay(0, 0);
});

document.getElementById('toggle-key').addEventListener('click', function () {
    const keyInput = document.getElementById('key');
    const toggleIcon = document.getElementById('toggle-key');

    if (keyInput.type === 'password') {
        keyInput.type = 'text';
        toggleIcon.src = '/icons/show.png';
    } else {
        keyInput.type = 'password';
        toggleIcon.src = '/icons/hide.png';
    }
});

document.getElementById('embed-button').addEventListener('click', async function () {
    const fileInput = document.getElementById('image-upload');
    const messageInput = document.getElementById('secret-message');
    const keyInput = document.getElementById('key');
    const file = fileInput.files[0];
    const message = messageInput.value;
    const key = keyInput.value;
    const keyError = document.getElementById('key-error');

    if (!file || !message || !key) {
        Swal.fire({
            icon: 'error',
            title: 'Missing Information',
            text: 'Please select an image, enter a message, and enter a key.'
        });
        return;
    }

    // Check key length
    if (key.length !== 16) {
        keyError.style.display = 'block';
        return;
    } else {
        keyError.style.display = 'none';
    }

    const formData = new FormData();
    formData.append('image', file);
    formData.append('message', message);
    formData.append('key', key);

    const processingText = document.getElementById('processing-text');
    processingText.style.display = 'block';

    try {
        console.log('Sending request...');
        const startTime = performance.now();

        const response = await fetch('/api/steganography/encode', {
            method: 'POST',
            body: formData,
        });

        if (!response.ok) {
            const errorText = await response.text();
            console.error('Response not OK:', errorText);

            Swal.fire({
                icon: 'error',
                title: 'Encoding Error',
                text: errorText || 'Failed to encode the image.'
            });

            return;
        }

        const endTime = performance.now();
        const duration = ((endTime - startTime) / 1000).toFixed(2);

        const imageBlob = await response.blob();
        const encodedImageElement = document.getElementById('encoded-image');
        encodedImageElement.src = URL.createObjectURL(imageBlob);
        document.querySelector('.encoded-image-section').style.display = 'block';

        document.getElementById('compilation-time').textContent = `Compilation time: ${duration} seconds`;

        document.getElementById('download-button').addEventListener('click', function () {
            const link = document.createElement('a');
            link.href = URL.createObjectURL(imageBlob);
            link.download = 'encoded_image.png';
            link.click();
        });
        generateEncodedHistogram(imageBlob);
    } catch (error) {
        console.error(error);
        Swal.fire({
            icon: 'error',
            title: 'Network Error',
            text: `Network Error: ${error.message}`
        });
    } finally {
        processingText.style.display = 'none';
    }
});
