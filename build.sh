#!/usr/bin/env bash

#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#


# 修改版本号
# mvn versions:set -DnewVersion=2.3-SNAPSHOP
# mvn version:commit
# rm -f `find ./ -name '*versionsBackup'`

##
## Variables with defaults (if not overwritten by environment)
##
RELEASE_VERSION=$1
SCALA_VERSION=$2
RELEASE_VERSION=${RELEASE_VERSION:-1.9-SNAPSHOT}
SCALA_VERSION=${SCALA_VERSION:-2.11}


if [ -z "${RELEASE_VERSION}" ]; then
    echo "RELEASE_VERSION was not set."
    exit 1
fi

if [ -z "${SCALA_VERSION}" ]; then
    echo "SCALA_VERSION was not set."
    exit 1
fi

# fail immediately
set -o errexit
set -o nounset
# print command before executing
set -o xtrace

FLINK_DIR=`pwd`
RELEASEING_DIR=${FLINK_DIR}/tools/releasing/

cd $RELEASEING_DIR

sh create_arjenlee_binary_release.sh $RELEASE_VERSION $SCALA_VERSION > $FLINK_DIR/build.log 2>&1 &
#sh create_arjenlee_binary_release.sh $RELEASE_VERSION $SCALA_VERSION 2>&1 &

# FLAGS="-Dscala-2.11"
# mvn clean package $FLAGS -Prelease -pl flink-dist -am -Dgpg.skip -Dmaven.javadoc.skip=true -Dcheckstyle.skip=true -DskipTests > build.log 2>&1 &
