Dockerized Jepsen
=================

This docker image attempts to simplify the setup required by Jepsen.
It is intended to be used by a CI tool or anyone with docker who wants to try jepsen themselves.

It contains all the jepsen dependencies and code. It uses [Docker Compose](https://github.com/docker/compose) to spin up the five
containers used by Jepsen.

By default the script creates five worker containers (`n1` to `n5`)
and one control container `jepsen-control`

You will need a docker machine with name `default` to run this script.

You can create a new docker machine using:

```
docker machine create default
```

To start run

````
./up.sh
````

To login into control image, you can run:

```
./in_control_container.sh
```


If you want to clean-up created docker images, you can run

```
./down.sh
```