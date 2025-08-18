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
            fetch('/api/login', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/x-www-form-urlencoded'
                },
                body: `email=${encodeURIComponent(email)}&password=${encodeURIComponent(password)}`
            })
                .then(async response => {
                    const contentType = response.headers.get('content-type');
                    if (contentType && contentType.indexOf('application/json') !== -1) {
                        const data = await response.json();
                        if (data.status === 'success') {
                            localStorage.setItem('loggedIn', 'true');
                            localStorage.setItem('email', email);

                            if (data.redirectUrl) {
                                window.location.href = data.redirectUrl;
                            } else {
                                window.location.href = '/home';
                            }
                        } else {
                            Swal.fire({
                                icon: 'error',
                                title: 'Login Error',
                                text: data.message || 'Login failed. Please try again.',
                            });
                        }
                    } else {
                        // If not JSON, check if redirected to /login?error=true
                        if (response.url && response.url.includes('/login?error=true')) {
                            Swal.fire({
                                icon: 'error',
                                title: 'Login Error',
                                text: 'Invalid email or password. Please try again.',
                            });
                        } else {
                            // If not redirected, assume login success and go to /home
                            window.location.href = '/home';
                        }
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
            event.preventDefault();

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
                    fetch('/logout', {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/x-www-form-urlencoded'
                        },
                        body: ''
                    })
                        .then(async response => {
                            // Try to parse JSON, fallback to redirect if not JSON
                            try {
                                const data = await response.json();
                                if (data.status === 'success') {
                                    localStorage.clear();
                                    sessionStorage.clear();
                                    window.location.href = '/login';
                                } else {
                                    Swal.fire({
                                        icon: 'error',
                                        title: 'Logout Failed',
                                        text: data.message || 'Logout failed. Please try again.',
                                    });
                                }
                            } catch (e) {
                                // If not JSON, just redirect
                                window.location.href = '/login';
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
            });
        });
    }
});
