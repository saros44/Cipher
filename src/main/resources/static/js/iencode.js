let chartInstance = null;

function clearCanvas(canvas) {
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);
}

function generateEncodedHistogram(imageBlob) {
    const histogramSection = document.querySelector('.histogram-section');
    const encodedImage = new Image();
    encodedImage.src = URL.createObjectURL(imageBlob);

    encodedImage.onload = function () {
        const encodedCanvas = document.getElementById('histogram-encoded');

        // Clear the canvas before drawing new histogram
        clearCanvas(encodedCanvas);

        const encodedHistogram = getImageHistogram(encodedImage, encodedCanvas);

        // Display histogram
        displayHistogram('histogram-encoded', encodedHistogram, 'Encoded Image Histogram');

        // Show the histogram section
        histogramSection.style.display = 'block';
    };
}

function getImageHistogram(imageElement, canvas) {
    const ctx = canvas.getContext('2d');
    ctx.drawImage(imageElement, 0, 0, canvas.width, canvas.height);

    const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
    const data = imageData.data;

    // Initialize arrays for RGB channels
    const histogram = {
        red: new Array(256).fill(0),
        green: new Array(256).fill(0),
        blue: new Array(256).fill(0)
    };

    // Iterate over pixel data
    for (let i = 0; i < data.length; i += 4) {
        histogram.red[data[i]]++;
        histogram.green[data[i + 1]]++;
        histogram.blue[data[i + 2]]++;
    }

    return histogram;
}

function displayHistogram(canvasId, histogram, title) {
    const ctx = document.getElementById(canvasId).getContext('2d');

    // If there's an existing chart, destroy it before creating a new one
    if (chartInstance) {
        chartInstance.destroy();
    }

    chartInstance = new Chart(ctx, {
        type: 'line',
        data: {
            labels: Array.from({ length: 256 }, (_, i) => i),
            datasets: [{
                label: 'Red',
                data: histogram.red,
                borderColor: 'rgb(211,28,66)',
                backgroundColor: 'rgba(255, 99, 132, 0.2)',
                fill: false
            }, {
                label: 'Green',
                data: histogram.green,
                borderColor: 'rgb(43,194,40)',
                backgroundColor: 'rgba(75, 192, 192, 0.2)',
                fill: false
            }, {
                label: 'Blue',
                data: histogram.blue,
                borderColor: 'rgb(34,55,189)',
                backgroundColor: 'rgba(54, 162, 235, 0.2)',
                fill: false
            }]
        },
        options: {
            responsive: true,
            plugins: {
                title: {
                    display: true,
                    text: title
                },
                tooltip: {
                    callbacks: {
                        title: function (context) {
                            return 'Pixel Intensity: ' + context[0].label;
                        },
                        label: function (context) {
                            return 'Frequency: ' + context.raw;
                        }
                    }
                }
            },
            scales: {
                x: {
                    type: 'linear',
                    position: 'bottom',
                    title: {
                        display: true,
                        text: 'Pixel Intensity',
                        color: '#fff',
                        font: {
                            size: 14,
                            weight: 'bold'
                        }
                    },
                    ticks: {
                        color: '#555'
                    },
                    grid: {
                        display: false
                    }
                },
                y: {
                    title: {
                        display: true,
                        text: 'Frequency',
                        color: '#fff',
                        font: {
                            size: 14,
                            weight: 'bold'
                        }
                    },
                    ticks: {
                        color: '#555'
                    },
                    grid: {
                        display: false
                    }
                }
            }
        }
    });
}

// --- New: exact capacity calculation matching encoder positions ---
const BLOCK_POSITIONS = [
    [0, 3, 5, 12, 15, 7, 2, 10],
    [1, 4, 6, 13, 14, 8, 9, 11],
    [2, 5, 7, 0, 12, 6, 1, 14],
    [3, 6, 0, 9, 15, 2, 4, 11]
];

function calculateMaxMessageLength(width, height) {
    // Reserve first row (y=0) for AES key as server does
    const availableHeight = Math.max(0, height - 1);
    if (availableHeight <= 0 || width <= 0) return 0;

    const blocksX = Math.ceil(width / 8);
    const blocksY = Math.ceil(availableHeight / 8);

    let capacityBits = 0;
    for (let by = 0; by < blocksY; by++) {
        for (let bx = 0; bx < blocksX; bx++) {
            const startX = bx * 8;
            const blockW = Math.min(8, width - startX);
            const blockH = Math.min(8, availableHeight - by * 8);
            if (blockW <= 0 || blockH <= 0) continue;

            const rowIdx = (bx + by) % BLOCK_POSITIONS.length;
            const positions = BLOCK_POSITIONS[rowIdx];

            for (const p of positions) {
                const localX = p % 8;
                const localY = Math.floor(p / 8);
                if (localX >= blockW || localY >= blockH) continue;
                capacityBits++;
            }
        }
    }

    // Capacity in bytes is floor(bits/8). Reserve one byte for the null terminator used by encoder.
    const capacityBytes = Math.floor(capacityBits / 8);
    return Math.max(0, capacityBytes - 1);
}

// Replace previous display function: show remaining characters (max - currentLength)
function updateRemainingDisplay(maxChars, currentLength) {
    const maxLengthElement = document.getElementById('max-length');
    const textarea = document.getElementById('secret-message');

    if (!maxLengthElement) return;

    const safeMax = Number.isFinite(maxChars) ? Math.max(0, Math.floor(maxChars)) : 0;
    const curLen = Number.isInteger(currentLength) ? Math.max(0, currentLength) : (textarea ? textarea.value.length : 0);

    const remaining = Math.max(0, safeMax - curLen);
    // Show label + remaining number (e.g. "characters: 123")
    maxLengthElement.textContent = 'characters: ' + String(remaining);
    maxLengthElement.dataset.maxChars = String(safeMax);

    // Ensure the textarea maxlength is kept in sync
    if (textarea) textarea.maxLength = safeMax;
}

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
            };
            img.src = e.target.result;
        };
        reader.readAsDataURL(file);
    } else {
        imgElement.src = '/icons/select.png';
        imgElement.classList.remove('active');
        updateRemainingDisplay(0, 0);
    }
});

// Update input event: decrease displayed number as characters are added
document.getElementById('secret-message').addEventListener('input', function () {
    const maxChars = parseInt(document.getElementById('max-length').dataset.maxChars || '0', 10);
    if (!Number.isFinite(maxChars)) return;

    if (this.value.length > maxChars) {
        this.value = this.value.slice(0, maxChars);
    }

    updateRemainingDisplay(maxChars, this.value.length);
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
        3
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
