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
        'core-concepts/wait-conditions',
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
        'guides/log-consumers',
        'guides/retry-and-backoff',
        'guides/troubleshooting',
      ],
    },
    {
      type: 'category',
      label: 'Integrations',
      collapsed: true,
      items: [
        'integrations/maven',
        'integrations/gradle',
        'integrations/paramixel',
      ],
    },
    {
      type: 'category',
      label: 'API Reference',
      collapsed: true,
      items: [
        'api/intro',
        'api/container-manager',
        'api/container-spec',
        'api/container',
        'api/network-manager',
        'api/network',
        'api/wait-conditions',
        'api/prefix-consumer',
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
