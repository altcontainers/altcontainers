// @ts-check

/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  docsSidebar: [
    'intro',
    {
      type: 'category',
      label: 'Getting Started',
      collapsed: false,
      items: [
        'getting-started/introduction',
        'getting-started/installation',
        'getting-started/first-container',
        'getting-started/project-setup',
      ],
    },
    {
      type: 'category',
      label: 'Core Concepts',
      collapsed: false,
      items: [
        'core-concepts/container-lifecycle',
        'core-concepts/wait-strategies',
        'core-concepts/networking',
        'core-concepts/resource-limits',
        'core-concepts/cleanup',
      ],
    },
    {
      type: 'category',
      label: 'Guides',
      collapsed: true,
      items: [
        'guides/configuration',
        'guides/log-consumers',
        'guides/retry-and-backoff',
        'guides/troubleshooting',
      ],
    },
    {
      type: 'category',
      label: 'API Reference',
      collapsed: true,
      items: [
        'api/intro',
        'api/container-spec',
        'api/container',
        'api/bind-mount',
        'api/network',
        'api/wait-strategies',
        'api/protocol',
        'api/ulimit',
        'api/container-exception',
        'api/version',
        'api/javadocs',
      ],
    },
    'release-notes',
  ],
};

module.exports = sidebars;
