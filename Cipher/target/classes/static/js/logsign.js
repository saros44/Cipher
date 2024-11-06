// Wait until the DOM is fully loaded
document.addEventListener('DOMContentLoaded', function () {

    // Function to validate email format using a regular expression
    function validateEmail(email) {
        const re = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
        return re.test(String(email).toLowerCase());
    }

    // Function to display error messages
// Function to clear error messages
    function clearErrorMessage() {
        const errorMessage = document.getElementById('errorMessage');
        if (errorMessage) {
            errorMessage.textContent = '';
            errorMessage.style.display = 'none';
        }
    }

    // Signup form validation and submission
    const signupForm = document.getElementById('signup-form');
    if (signupForm) {
        signupForm.addEventListener('submit', function (event) {
            event.preventDefault(); // Prevent the default form submission

            // Get email and password values
            const email = document.getElementById('email').value.trim();
            const password = document.getElementById('password').value.trim();

            // Clear previous error messages (if needed)
            clearErrorMessage();

            // Validate email and password
            if (!validateEmail(email)) {
                Swal.fire({
                    icon: 'error',
                    title: 'Invalid Email',
                    text: 'Please enter a valid email address.',
                });
                return;
            }

            if (password.length < 6) {
                Swal.fire({
                    icon: 'error',
                    title: 'Invalid Password',
                    text: 'Password must be at least 6 characters long.',
                });
                return;
            }

            // Send signup request to the server
            fetch('/signup', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/x-www-form-urlencoded'
                },
                body: `email=${encodeURIComponent(email)}&password=${encodeURIComponent(password)}`
            })
                .then(response => response.json())
                .then(data => {
                    if (data.status === 'success') {
                        Swal.fire({
                            icon: 'success',
                            title: 'Signup Successful',
                            text: 'Redirecting to login page...',
                            timer: 2000,
                            showConfirmButton: false
                        }).then(() => {
                            window.location.href = '/login'; // Redirect to login page after signup success
                        });
                    } else {
                        Swal.fire({
                            icon: 'error',
                            title: 'Signup Error',
                            text: data.message || 'Signup failed. Please try again.',
                        });
                    }
                })
                .catch(error => {
                    console.error('Error:', error);
                    Swal.fire({
                        icon: 'error',
                        title: 'Signup Error',
                        text: 'An error occurred during signup. Please try again later.',
                    });
                });
        });
    }


// Login form validation and submission
    const loginForm = document.getElementById('login-form');
    if (loginForm) {
        loginForm.addEventListener('submit', function (event) {
            event.preventDefault(); // Prevent the default form submission

            // Get email and password values
            const email = document.getElementById('email').value.trim();
            const password = document.getElementById('password').value.trim();

            // Clear previous error messages (if using any other UI elements for error display)
            clearErrorMessage();

            // Validate email and password
            if (!validateEmail(email)) {
                Swal.fire({
                    icon: 'error',
                    title: 'Invalid Email',
                    text: 'Please enter a valid email address.',
                });
                return;
            }

            if (password.length < 6) {
                Swal.fire({
                    icon: 'error',
                    title: 'Invalid Password',
                    text: 'Password must be at least 6 characters long.',
                });
                return;
            }

            // Send login request to the server
            fetch('/login', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/x-www-form-urlencoded'
                },
                body: `email=${encodeURIComponent(email)}&password=${encodeURIComponent(password)}`
            })
                .then(response => response.json())
                .then(data => {
                    if (data.status === 'success') {
                        // Store login status in localStorage
                        localStorage.setItem('loggedIn', 'true');
                        localStorage.setItem('email', email);

                        // Redirect to home page
                        if (data.redirectUrl) {
                            window.location.href = data.redirectUrl;
                        } else {
                            window.location.href = '/home'; // Default redirect if no URL provided
                        }
                    } else {
                        Swal.fire({
                            icon: 'error',
                            title: 'Login Error',
                            text: data.message || 'Login failed. Please try again.',
                        });
                    }
                })
                .catch(error => {
                    console.error('Error:', error);
                    Swal.fire({
                        icon: 'error',
                        title: 'Login Error',
                        text: 'An error occurred during login. Please try again later.',
                    });
                });
        });
    }


    const logoutBtn = document.getElementById('logoutBtn');
    if (logoutBtn) {
        logoutBtn.addEventListener('click', function (event) {
            event.preventDefault(); // Prevent default action on click

            // Show confirmation popup using SweetAlert2
            Swal.fire({
                title: 'Are you sure?',
                text: 'Please confirm if you want to logout',
                icon: 'warning',
                showCancelButton: true,
                confirmButtonColor: '#3085d6',
                cancelButtonColor: '#d33',
                confirmButtonText: 'Yes',
                cancelButtonText: 'No'
            }).then((result) => {
                if (result.isConfirmed) {
                    // If confirmed, proceed with the logout request
                    fetch('/logout', {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/json'
                        }
                    })
                        .then(response => response.json())
                        .then(data => {
                            if (data.status === 'success') {
                                // Clear localStorage or sessionStorage (if used)
                                localStorage.clear();
                                sessionStorage.clear();

                                // Redirect to the login page
                                window.location.href = '/login';
                            } else {
                                Swal.fire({
                                    icon: 'error',
                                    title: 'Logout Failed',
                                    text: 'Logout failed. Please try again.',
                                });
                            }
                        })
                        .catch(error => {
                            console.error('Error:', error);
                            Swal.fire({
                                icon: 'error',
                                title: 'Error',
                                text: 'An error occurred during logout. Please try again later.',
                            });
                        });
                }
                // If user clicks "No, stay logged in", do nothing and remain on the current page
            });
        });
    }
});


