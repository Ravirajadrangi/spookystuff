from __future__ import print_function

import logging
import time

import dronekit

from pyspookystuff.mav import Const
from pyspookystuff.utils import retry


@retry(Const.armRetries)
def assureInTheAir(targetAlt, vehicle):
    alt = vehicle.location.global_relative_frame.alt + 1
    if alt >= targetAlt:
        logging.info("already in the air")
    else:
        armIfNot(vehicle)
        blockingTakeoff(targetAlt, vehicle)
    alt = vehicle.location.global_relative_frame.alt + 1
    assert alt >= targetAlt


def armIfNot(vehicle):
    if not vehicle.armed:
        blockingArm(vehicle)


def blockingArm(vehicle):
    # type: (dronekit.Vehicle) -> None
    # Don't let the user try to fly when autopilot is booting
    i = 60
    while not vehicle.is_armable and i > 0:
        time.sleep(1)
        i -= 1

    # Copter should arm in GUIDED mode
    vehicle.mode = dronekit.VehicleMode("GUIDED")
    i = 60
    while vehicle.mode.name != 'GUIDED' and i > 0:
        print(" Waiting for guided %s seconds..." % (i,))
        time.sleep(1)
        i -= 1

    # Arm copter.
    vehicle.armed = True
    i = 60
    while not vehicle.armed and vehicle.mode.name == 'GUIDED' and i > 0:
        print(" Waiting for arming %s seconds..." % (i,))
        time.sleep(1)
        i -= 1


def blockingTakeoff(targetAltitude, vehicle):

    # Wait until the vehicle reaches a safe height before
    # processing the goto (otherwise the command after
    # Vehicle.simple_takeoff will execute immediately).
    while True:
        alt = vehicle.location.global_relative_frame.alt

        if alt <= 0.1:
            print("taking off from the ground ... ")
            vehicle.simple_takeoff(targetAltitude)

        noTimeout(vehicle)
        print(" Altitude: ", alt)
        # Test for altitude just below target, in case of undershoot.
        if alt >= targetAltitude * 0.95:
            print("Reached target altitude")
            break

        time.sleep(1)


def noTimeout(vehicle):
    assert(vehicle.last_heartbeat < 10)