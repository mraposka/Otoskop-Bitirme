<?php
require_once __DIR__ . '/lib.php';
redirect(current_user() ? 'gallery.php' : 'login.php');
