// The Kotlin/JS test bundle imports the Skiko runtime through relative paths
// ("./skiko.mjs", "./js-reexport-symbols.mjs") that the wasm test package does not
// physically contain: this module pulls Compose/Skiko transitively via domain-core,
// and the plugin only copies the runtime into executable (binaries.executable) packages.
// Redirect those relative requests to the bare "skiko-js-wasm-runtime" module, which is
// present in the workspace node_modules and resolves like the other bare imports
// (e.g. "@js-joda/core") the generated bundle already relies on.
const webpack = require('webpack');

config.plugins = (config.plugins || []).concat([
    new webpack.NormalModuleReplacementPlugin(
        /^\.\/skiko\.mjs$/,
        'skiko-js-wasm-runtime/skiko.mjs',
    ),
    new webpack.NormalModuleReplacementPlugin(
        /^\.\/js-reexport-symbols\.mjs$/,
        'skiko-js-wasm-runtime/js-reexport-symbols.mjs',
    ),
]);
