/* eslint-disable import/no-commonjs */

const path = require('path');
const exclusionList = require('metro-config/src/defaults/exclusionList');
const escape = require('escape-string-regexp');
const pack = require('../package.json');
const {mergeConfig, getDefaultConfig} = require('@react-native/metro-config');

const root = path.resolve(__dirname, '..');

const modules = [
  '@react-navigation/native',
  'react-navigation',
  'react-navigation-stack',
  'react-native-reanimated',
  'react-native-safe-area-context',
  ...Object.keys(pack.peerDependencies),
];

/**
 * Custom Metro configuration
 * https://facebook.github.io/metro/docs/configuration
 * @type {import('metro-config').MetroConfig}
 */
const config = {
  projectRoot: __dirname,
  watchFolders: [root],

  // We need to make sure that only one version is loaded for peerDependencies
  // So we exclude them at the root, and alias them to the versions in example's node_modules
  resolver: {
    blacklistRE: exclusionList(
      modules.map(
        (m) =>
          new RegExp(`^${escape(path.join(root, 'node_modules', m))}\\/.*$`),
      ),
    ),

    extraNodeModules: modules.reduce((acc, name) => {
      acc[name] = path.join(__dirname, 'node_modules', name);
      return acc;
    }, {}),
  },
};

module.exports = mergeConfig(getDefaultConfig(__dirname, config));
