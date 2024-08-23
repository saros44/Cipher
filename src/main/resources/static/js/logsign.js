// JavaScript for signup and login forms
// Wait until the DOM is fully loaded
document.addEventListener('DOMContentLoaded', function () {

    // Select the signup form by its ID and add an event listener for submission
    const signupForm = document.getElementById('signup-form');
    if (signupForm) {
        signupForm.addEventListener('submit', function (event) {
            event.preventDefault(); // Prevent the default form submission

            // Validate the form fields
            const email = document.getElementById('email').value.trim();
            const password = document.getElementById('password').value.trim();

            // Simple validation
            if (!validateEmail(email)) {
                alert('Please enter a valid email address.');
            }

            if (password.length < 6) {
                alert('Password must be at least 6 characters long.');
            }

            if (password !== confirmPassword) {
                alert('Passwords do not match.');

            }

            // If validation passes, submit the form
            signupForm.submit();
        });
    }

    // Select the login form by its ID and add an event listener for submission
    const loginForm = document.getElementById('login-form');
    if (loginForm) {
        loginForm.addEventListener('submit', function (event) {
            event.preventDefault(); // Prevent the default form submission

            // Validate the form fields
            const email = document.getElementById('email').value.trim();
            const password = document.getElementById('password').value.trim();

            // Simple validation
            if (!validateEmail(email)) {
                alert('Please enter a valid email address.');
                return;
            }

            if (password.length < 6) {
                alert('Password must be at least 6 characters long.');
                return;
            }

            // If validation passes, submit the form
            loginForm.submit();
        });
    }

    // Function to validate email format using a regular expression
    function validateEmail(email) {
        const re = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
        return re.test(String(email).toLowerCase());
    }

});
