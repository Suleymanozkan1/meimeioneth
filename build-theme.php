<?php
/**
 * Theme Zip Generator
 *
 * Run this file to generate a WordPress theme zip file
 * php build-theme.php
 *
 * Only the theme's own files are packaged (allowlist below). This repository also contains the LexiFlow app and
 * backend, whose local secret files (backend/.env, keystore.properties, keystores) must never end up in a theme
 * that is served from wp-content/themes/.
 */

$theme_name = 'meimei-eco';

// Top-level files and directories that belong to the WordPress theme.
$include_roots = array(
    'style.css',
    'screenshot.png',
    'functions.php',
    'header.php',
    'footer.php',
    'index.php',
    'inc',
    'template-parts',
    'assets',
    'languages',
    'vendor',
);

// Never packaged, even inside an included directory (matched against each path segment).
$exclude_names = array(
    '.git', '.github', '.DS_Store', 'node_modules',
    '.env', '.env.*', '*.jks', '*.keystore', '*.p12', '*.pem', '*.key',
    'keystore.properties', 'local.properties', '*.log', '*.map', '*.zip',
);

// Create zip archive
$zip = new ZipArchive();
$zip_name = "{$theme_name}.zip";

if ($zip->open($zip_name, ZipArchive::CREATE | ZipArchive::OVERWRITE) !== TRUE) {
    die("Could not create zip archive\n");
}

// Helper function to check if a repository-relative path should be packaged
function should_include($relative_path, $include_roots, $exclude_names) {
    $segments = explode('/', str_replace('\\', '/', $relative_path));
    if (!in_array($segments[0], $include_roots, true)) {
        return false;
    }
    foreach ($segments as $segment) {
        foreach ($exclude_names as $pattern) {
            if (fnmatch($pattern, $segment)) {
                return false;
            }
        }
    }
    return true;
}

// Add files recursively
function add_files_to_zip($zip, $root, $include_roots, $exclude_names) {
    $files = new RecursiveIteratorIterator(
        new RecursiveDirectoryIterator($root, FilesystemIterator::SKIP_DOTS),
        RecursiveIteratorIterator::LEAVES_ONLY
    );

    foreach ($files as $file) {
        // Skip directories and symlinks (a symlink could point outside the theme).
        if ($file->isDir() || $file->isLink()) {
            continue;
        }

        $relativePath = substr($file->getPathname(), strlen($root) + 1);

        if (should_include($relativePath, $include_roots, $exclude_names)) {
            $zip->addFile($file->getPathname(), $relativePath);
        }
    }
}

// Add theme files
add_files_to_zip($zip, __DIR__, $include_roots, $exclude_names);

// Close zip archive
$zip->close();

echo "Theme zip file created successfully: {$zip_name}\n";
