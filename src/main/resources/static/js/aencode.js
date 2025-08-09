let encodedAudioBlob = null;
let snrChartInstance = null;
let maxMessageLength = 0;

function toggleKeyVisibility() {
    const keyInput = document.getElementById('key');
    const toggleIcon = document.getElementById('toggleKey');

    if (keyInput.type === "password") {
        keyInput.type = "text";
        toggleIcon.src = "/icons/show.png";
    } else {
        keyInput.type = "password";
        toggleIcon.src = "/icons/hide.png";
    }
}

function previewAudio() {
    const fileInput = document.getElementById('inputFile');
    const file = fileInput.files[0];
    const previewAudio = document.getElementById('previewAudio');
    const previewSection = document.getElementById('previewSection');

    if (file) {
        previewAudio.src = URL.createObjectURL(file);
        previewSection.style.display = 'block';

        // Calculate max message length as 50% of the audio file size (example logic)
        const fileSizeInBytes = file.size;
        maxMessageLength = Math.floor(fileSizeInBytes / 8 * 0.5); // 50% of max capacity
        document.getElementById('remainingCount').innerText = maxMessageLength;

        // Reset the message area
        document.getElementById('message').value = '';
        updateCharacterCount(); // Update the character count display
    } else {
        previewSection.style.display = 'none';
    }
}

function updateCharacterCount() {
    const message = document.getElementById('message').value.trim();
    const remainingCountElement = document.getElementById('remainingCount');
    const remainingChars = maxMessageLength - message.length;

    remainingCountElement.innerText = remainingChars >= 0 ? remainingChars : 0;

    // Disable typing if remaining characters are 0
    if (remainingChars < 0) {
        document.getElementById('message').value = message.substring(0, maxMessageLength);
        remainingCountElement.innerText = 0; // Ensure it shows 0
    }
}

function submitEncodeForm() {
    const fileInput = document.getElementById('inputFile').files[0];
    const message = document.getElementById('message').value.trim();
    const key = document.getElementById('key').value.trim();

    if (!fileInput || !message) {
        Swal.fire({
            icon: 'error',
            title: 'Missing Information',
            text: 'Please select an audio file, enter a message, and enter a key.'
        });
        return;
    }
    if (key.length === 0 || key.length < 8) {
        Swal.fire({
            icon: 'error',
            title: 'Server Error',
            text: 'Please enter a valid key.',
        });
        return;
    }

    const formData = new FormData();
    formData.append('audio', fileInput);
    formData.append('message', message);
    formData.append('key', key);

    const startTime = performance.now(); // Start time tracking

    fetch('/api/steganography/encode-audio', {
        method: 'POST',
        body: formData
    })
        .then(response => {
            if (!response.ok) {
                throw new Error(`error: ${response.status}`);
            }
            return response.json();
        })
        .then(data => {
            const endTime = performance.now(); // End time tracking
            const duration = (endTime - startTime) / 1000; // Convert to seconds
            document.getElementById('compilationTime').innerText = `Compilation Time: ${duration.toFixed(2)} seconds`;

            if (data.error) {
                Swal.fire({
                    icon: 'error',
                    title: 'Server Error',
                    text: data.error,
                });
                return;
            }

            if (data.snrStages && data.encodedAudio) {
                displayEncodedAudioAndSNR(data);
            } else {
                Swal.fire({
                    icon: 'error',
                    title: 'Server Error',
                    text: 'No SNR data or encoded audio returned.',
                });
            }
        })
        .catch(error => {
            Swal.fire({
                icon: 'error',
                title: 'Server Error',
                text: "Error during encoding: " + error.message,
            });
        });
}

function downloadFile() {
    if (encodedAudioBlob) {
        const link = document.createElement('a');
        link.href = URL.createObjectURL(encodedAudioBlob);
        link.download = 'encoded_audio.wav';
        link.click();
        URL.revokeObjectURL(link.href);
    } else {
        Swal.fire({
            icon: 'error',
            title: 'Server Error',
            text: 'No encoded audio available.',
        });
    }
}

function displayEncodedAudioAndSNR(response) {
    const encodedAudioBase64 = response.encodedAudio;
    const snrStages = response.snrStages;

    if (!snrStages) {
        Swal.fire({
            icon: 'error',
            title: 'Server Error',
            text: 'SNR stages data not found.',
        });
        return;
    }

    const audioData = atob(encodedAudioBase64);
    const audioBytes = new Uint8Array(audioData.length);
    for (let i = 0; i < audioData.length; i++) {
        audioBytes[i] = audioData.charCodeAt(i);
    }

    encodedAudioBlob = new Blob([audioBytes], {type: 'audio/wav'});
    document.getElementById('encodedFileSection').style.display = 'block';

    createDetailedSNRChart(snrStages);
}

function createDetailedSNRChart(snrStages) {
    const ctx = document.getElementById('snrFlowChart').getContext('2d');

    if (snrChartInstance) {
        snrChartInstance.destroy();
    }

    const stageLabels = [
        'Initial',
        'Key',
        'Message Length',
        'Message',
        'Delimiter',
        'Final'
    ];

    const chartLabels = [];
    const chartData = [];

    chartLabels.push(stageLabels[0]);
    chartData.push(0);

    for (let i = 0; i < snrStages.length; i++) {
        if (i < stageLabels.length - 1) {
            chartLabels.push(stageLabels[i + 1]);
            chartData.push(snrStages[i]);
        }
    }

    snrChartInstance = new Chart(ctx, {
        type: 'line',
        data: {
            labels: chartLabels,
            datasets: [{
                label: 'SNR Levels',
                data: chartData,
                borderColor: 'rgba(75, 192, 192, 1)',
                fill: false,
                tension: 0.1
            }]
        },
        options: {
            scales: {
                y: {
                    beginAtZero: true,
                    title: {
                        display: true,
                        text: 'SNR'
                    }
                },
                x: {
                    title: {
                        display: true,
                        text: 'Stages'
                    }
                }
            }
        }
    });

    const snrDescription = document.getElementById('snrDescription');
    snrDescription.innerText = 'SNR (Signal-to-Noise Ratio) provides insights into the quality of the audio after encoding. Higher values indicate better quality.';
    document.getElementById('snrChartSection').style.display = 'block';
}

