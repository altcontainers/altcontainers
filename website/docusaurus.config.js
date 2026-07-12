// @ts-check
const {themes} = require('prism-react-renderer');
const lightCodeTheme = themes.github;
const darkCodeTheme = themes.dracula;

const baseUrl = '/';

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'Altcontainers',
  tagline: 'Altcontainers — lightweight Docker container lifecycle management for Java 17+',
  favicon: 'img/favicon.ico',

  url: 'https://www.altcontainers.org',
  baseUrl,

  onBrokenLinks: 'throw',

  headTags: [
    {
      tagName: 'link',
      attributes: {
        rel: 'icon',
        type: 'image/svg+xml',
        href: `${baseUrl}img/logo.svg`,
      },
    },
  ],

  markdown: {
    hooks: {
      onBrokenMarkdownLinks: 'throw',
    },
  },

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          routeBasePath: '/',
          sidebarPath: require.resolve('./sidebars.js'),
          editUrl: 'https://github.com/altcontainers/altcontainers/tree/main/website/',
          lastVersion: '0.2.0',
          versions: {
            current: {
              label: 'Unreleased',
              path: 'unreleased',
              banner: 'unreleased',
              badge: true,
            },
            '0.2.0': {
              banner: 'none',
            },
            '0.1.0': {
              banner: 'unmaintained',
              label: '0.1.0 (Unmaintained)',
              className: 'notice-unmaintained',
            },
          },
        },
        theme: {
          customCss: require.resolve('./src/css/custom.css'),
        },
      }),
    ],
  ],

  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      docs: {
        sidebar: {
          hideable: true,
          autoCollapseCategories: true,
        },
      },
      navbar: {
        title: 'Altcontainers',
        logo: {
          alt: 'Altcontainers Logo',
          src: 'img/logo.svg',
        },
        items: [
          {
            type: 'docSidebar',
            sidebarId: 'docsSidebar',
            position: 'left',
            label: 'Documentation',
          },
          {
            type: 'docsVersionDropdown',
            position: 'left',
          },
          {
            href: 'https://github.com/altcontainers/altcontainers',
            label: 'GitHub',
            position: 'right',
          },
          {
            href: 'https://central.sonatype.com/search?namespace=org.altcontainers',
            label: 'Maven Central',
            position: 'right',
          },
        ],
      },
      footer: {
        style: 'dark',
        links: [
          {
            title: 'Documentation',
            items: [
              { label: 'Getting Started', to: '/getting-started/introduction' },
              { label: 'Core Concepts', to: '/core-concepts/container-lifecycle' },
              { label: 'API Reference', to: '/api/intro' },
            ],
          },
          {
            title: 'Community',
            items: [
              {
                label: 'GitHub',
                href: 'https://github.com/altcontainers/altcontainers',
              },
              {
                label: 'Issues',
                href: 'https://github.com/altcontainers/altcontainers/issues',
              },
            ],
          },
        ],
        copyright: `Copyright © ${new Date().getFullYear()} Douglas Hoard. Licensed under Apache License 2.0.`,
      },
      prism: {
        theme: lightCodeTheme,
        darkTheme: darkCodeTheme,
        additionalLanguages: ['java', 'bash', 'yaml', 'json', 'markdown', 'properties'],
      },
      colorMode: {
        defaultMode: 'light',
        disableSwitch: false,
        respectPrefersColorScheme: true,
      },
    }),
};

module.exports = config;
