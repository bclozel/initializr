# Spring Initializr

## Prerequisites

You need Java (1.6 or better) and a bash-like shell. To get started,
some basic operating system tools (`curl` and `zip`).  The `spring`
command line interface can be installed like this

    $ curl spring.cfapps.io/installer | bash

After running that command you should see a `spring` directory:

    $ ./spring/bin/spring --help
    
    usage: spring [--help] [--version] 
       <command> [<args>]
    ...

You could add that `bin` directory to your `PATH` (the examples below
assume you did that).

If you don't have `curl` or `zip` you can probably get them (for
Windoze users we recommend [cygwin](http://cygwin.org)), or you can
download the [zip file](http://spring.cfapps.io/spring.zip) and unpack
it yourself.

## Running the app

Use the spring command:

    $ spring run app.groovy

