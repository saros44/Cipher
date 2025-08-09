document.getElementById('inputFile').addEventListener('change', function (event) {
    const file = event.target.files[0];
    const audioPreview = document.getElementById('audioPreview');
    const audioPreviewSection = document.getElementById('audioPreviewSection');

    if (file) {
        audioPreview.src = URL.createObjectURL(file);
        audioPreviewSection.style.display = 'block';
    } else {
        audioPreviewSection.style.display = 'none';
    }
});

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

async function submitDecodeForm() {
    const fileInput = document.getElementById('inputFile').files[0];
    const key = document.getElementById('key').value.trim();

    // Validate file and key
    if (!fileInput) {
        Swal.fire({
            icon: 'warning',
            title: 'No File Selected',
            text: 'Please select a WAV file.',
        });
        return;
    }
    if (key.length === 0) {
        Swal.fire({
            icon: 'warning',
            title: 'Key Missing',
            text: 'Please enter the key.',
        });
        return;
    }

    const formData = new FormData();
    formData.append('audio', fileInput);
    formData.append('key', key);

    const startTime = performance.now(); // Start time tracking

    try {
        const response = await fetch('/api/steganography/decode-audio', {
            method: 'POST',
            body: formData
        });

        const message = await response.text();

        const endTime = performance.now(); // End time tracking
        const duration = (endTime - startTime) / 1000; // Convert to seconds
        document.getElementById('compilationTime').innerText = `Compilation Time: ${duration.toFixed(2)} seconds`;
        document.getElementById('compilationTime').style.display = 'block'; // Show compilation time

        if (response.ok) {
            if (message.startsWith('Audio decoding failed:') || message.startsWith('Error:')) {
                Swal.fire({
                    icon: 'error',
                    title: 'Decoding Failed',
                    text: message,
                });
            } else {
                const decodedMessageSection = document.getElementById('decodedMessageSection');
                const decodedMessageDiv = document.getElementById('decoded-message');
                const decodedMessageHeading = document.getElementById('decoded-message-heading');

                decodedMessageDiv.textContent = message.trim() === '' ? 'No message found !!!' : message;
                decodedMessageHeading.style.display = 'block';
                decodedMessageSection.style.display = 'block';
            }
        } else {
            Swal.fire({
                icon: 'error',
                title: 'Server Error',
                text: message,
            });
        }
    } catch (error) {
        console.error('Error:', error);
        Swal.fire({
            icon: 'error',
            title: 'Request Failed',
            text: 'Failed to decode message.',
        });
    }
}
