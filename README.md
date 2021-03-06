SMTInterpol
===========

This is SMTInterpol, an interpolating SMT-solver developed at the university
of Freiburg.  You can find more information on the website

http://ultimate.informatik.uni-freiburg.de/smtinterpol/


Compilation
-----------

To compile SMTInterpol you need:
- Java (at least version 1.6)
- Apache ant

SMTInterpol comes with an ant build file that compiles the sources into a bin
folder (will be created by the build), and creates a standalone jar.  You can
run it with

    ant


Usage
-----

To run SMTInterpol from command line you need the standalone jar.  Run it as

    java -jar smtinterpol.jar

and pass the necessary commands to the standard input of this process.
Alternatively you can specify a SMTLIB 2 script file as argument to the
process.  In either case, SMTInterpol will parse and execute commands until it
an exit command or the end of the input stream.


Integration into Eclipse
------------------------

The source distribution of SMTInterpol is an Eclipse project.  If you want to
use this project, you can easily import it into Eclipse as "Existing Project
into Workspace".


Reporting Bugs
--------------

There is no bug tracking system for SMTInterpol yet.  Simply send a bug report
to hoenicke@informatik.uni-freiburg.de with all needed information.  This
includes:
 -  a description of the bug (e.g., crash, unsoundness, or feature-request),
 -  a way to reproduce the bug (e.g., an interaction log with the solver 
    via the LoggingScript provided with the sources), and
 -  contact data (e.g., an email address) used for communication.

```
(declare-fun in (using) SMTInterpol)
```
