document.getElementById('custom-image-upload').addEventListener('change', function (event) {
    const file = event.target.files[0];
    if (file) {
        const reader = new FileReader();
        reader.onload = function (e) {
            const previewImage = document.getElementById('image-preview');
            previewImage.src = e.target.result;
            previewImage.classList.add('uploaded');
        };
        reader.readAsDataURL(file);
    }
});

document.getElementById('decode-btn').addEventListener('click', async function () {
    const fileInput = document.getElementById('custom-image-upload');
    const keyField = document.getElementById('key-field');
    const keyError = document.getElementById('key-error');
    const decodedMessageDiv = document.getElementById('decoded-message');
    const decodedMessageHeading = document.getElementById('decoded-message-heading');
    const compilationTimeDiv = document.getElementById('decode-compilation-time');
    const loadingMessage = document.getElementById('processing-text');

    if (fileInput.files.length === 0) {
        Swal.fire({
            icon: 'error',
            title: 'Image Required',
            text: 'Please upload an image first.'
        });
        return;
    }

    const file = fileInput.files[0];
    const key = keyField.value.trim();

    // Validate key length
    if (key.length !== 16) {
        keyError.style.display = 'block';
        return;
    } else {
        keyError.style.display = 'none';
    }

    const formData = new FormData();
    formData.append('image', file);
    formData.append('key', key);

    const startTime = performance.now();

    loadingMessage.style.display = 'block';
    decodedMessageHeading.style.display = 'none';
    decodedMessageDiv.style.display = 'none';
    compilationTimeDiv.style.display = 'none';

    try {
        const response = await fetch('/api/steganography/decode', {
            method: 'POST',
            body: formData
        });

        const endTime = performance.now();
        const compilationTime = ((endTime - startTime) / 1000).toFixed(2);

        if (response.ok) {
            const message = await response.text();
            if (message.startsWith('Error:')) {
                Swal.fire({
                    icon: 'error',
                    title: 'Decoding Error',
                    text: message
                });
            } else {
                decodedMessageDiv.textContent = message.trim() === '' ? 'No message found !!!' : message;
                decodedMessageHeading.style.display = 'block';
                decodedMessageDiv.style.display = 'block';
            }
        } else {
            const errorMessage = await response.text();
            Swal.fire({
                icon: 'error',
                title: 'Server Error',
                text: `Server Error: ${errorMessage}`
            });
        }

        loadingMessage.style.display = 'none';
        compilationTimeDiv.textContent = `Compilation time: ${compilationTime} seconds`;
        compilationTimeDiv.style.display = 'block';

    } catch (error) {
        Swal.fire({
            icon: 'error',
            title: 'Network Error',
            text: `Network Error: ${error.message}`
        });

        const endTime = performance.now();
        const compilationTime = ((endTime - startTime) / 1000).toFixed(2);

        loadingMessage.style.display = 'none';
        compilationTimeDiv.textContent = `Compilation time: ${compilationTime} seconds`;
        compilationTimeDiv.style.display = 'block';
    }
});

// Show/Hide key input
document.getElementById('toggle-key-field').addEventListener('click', function () {
    const keyInput = document.getElementById('key-field');
    const toggleIcon = document.getElementById('toggle-key-field');

    if (keyInput.type === 'password') {
        keyInput.type = 'text';
        toggleIcon.src = '/icons/show.png';
    } else {
        keyInput.type = 'password';
        toggleIcon.src = '/icons/hide.png';
    }
});
