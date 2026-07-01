import React from 'react';
import ComponentCreator from '@docusaurus/ComponentCreator';

export default [
  {
    path: '/blog',
    component: ComponentCreator('/blog', '98b'),
    exact: true
  },
  {
    path: '/',
    component: ComponentCreator('/', 'b3f'),
    routes: [
      {
        path: '/',
        component: ComponentCreator('/', '0e0'),
        routes: [
          {
            path: '/',
            component: ComponentCreator('/', 'c6e'),
            routes: [
              {
                path: '/api/container',
                component: ComponentCreator('/api/container', '44a'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/api/container-exception',
                component: ComponentCreator('/api/container-exception', 'dff'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/api/container-manager',
                component: ComponentCreator('/api/container-manager', '903'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/api/container-spec',
                component: ComponentCreator('/api/container-spec', '20f'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/api/intro',
                component: ComponentCreator('/api/intro', '636'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/api/javadocs',
                component: ComponentCreator('/api/javadocs', '8db'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/api/network',
                component: ComponentCreator('/api/network', '385'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/api/network-manager',
                component: ComponentCreator('/api/network-manager', '2e1'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/api/prefix-consumer',
                component: ComponentCreator('/api/prefix-consumer', 'b64'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/api/ulimit',
                component: ComponentCreator('/api/ulimit', 'd15'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/api/version',
                component: ComponentCreator('/api/version', '011'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/api/wait-conditions',
                component: ComponentCreator('/api/wait-conditions', '72d'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/core-concepts/cleanup',
                component: ComponentCreator('/core-concepts/cleanup', 'c18'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/core-concepts/container-lifecycle',
                component: ComponentCreator('/core-concepts/container-lifecycle', '544'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/core-concepts/networking',
                component: ComponentCreator('/core-concepts/networking', '19a'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/core-concepts/resource-limits',
                component: ComponentCreator('/core-concepts/resource-limits', '6b2'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/core-concepts/wait-conditions',
                component: ComponentCreator('/core-concepts/wait-conditions', '986'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/getting-started/first-container',
                component: ComponentCreator('/getting-started/first-container', 'be4'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/getting-started/installation',
                component: ComponentCreator('/getting-started/installation', '91c'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/getting-started/introduction',
                component: ComponentCreator('/getting-started/introduction', 'af6'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/getting-started/project-setup',
                component: ComponentCreator('/getting-started/project-setup', '11b'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/guides/log-consumers',
                component: ComponentCreator('/guides/log-consumers', '0b0'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/guides/retry-and-backoff',
                component: ComponentCreator('/guides/retry-and-backoff', 'a02'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/guides/troubleshooting',
                component: ComponentCreator('/guides/troubleshooting', '77a'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/integrations/gradle',
                component: ComponentCreator('/integrations/gradle', 'eb7'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/integrations/maven',
                component: ComponentCreator('/integrations/maven', 'b7a'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/integrations/paramixel',
                component: ComponentCreator('/integrations/paramixel', 'bc1'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/release-notes',
                component: ComponentCreator('/release-notes', '2ac'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/',
                component: ComponentCreator('/', 'f9d'),
                exact: true,
                sidebar: "docsSidebar"
              }
            ]
          }
        ]
      }
    ]
  },
  {
    path: '*',
    component: ComponentCreator('*'),
  },
];
