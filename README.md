# foreman-windows-agent

## Status ##

![Build Status](https://github.com/foremanmining/foreman-windows-agent/actions/workflows/workflow.yml/badge.svg)

## Windows Agent ##

The Foreman Windows Agent is an open-source Java application that will 
automatically download, install, and configure the applications that are used
to extract metrics from cryptocurrency miners and upload them directly to 
Foreman.  

Additionally, this agent will monitor the Foreman [application manifests](https://dashboard.foreman.mn/api/manifests) for newer versions of applications and automatically upgrade them.

You can find the documentation and getting started guides for this agent on the [Foreman dashboard](https://dashboard.foreman.mn/dashboard/support/pickaxe/).  Note: you must be logged in to view the dashboard and all guides.

At a high-level,

1. Download agent [(latest)](https://github.com/delawr0190/foreman-windows-agent/releases).
2. Unzip agent.
3. Add your API information from [here](https://dashboard.foreman.mn/dashboard/profile/) to `conf/foreman.txt`.
4. Start agent via `bin\service-start.bat`.
5. Add miners from your dashboard! :)

## License ##

Copyright © 2020, [OBM LLC](https://obm.mn/).  Released under the [GPL-3.0 License](LICENSE).
