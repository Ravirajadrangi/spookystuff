from __future__ import print_function
import time
from dronekit import connect, VehicleMode, LocationGlobalRelative
from python.test import with_sitl, with_sitl_udp, with_sitl_multi
from nose.tools import assert_equals


@with_sitl_multi
def test_ferry(connpath):
    vehicle = connect(connpath, wait_ready=True)

    # NOTE these are *very inappropriate settings*
    # to make on a real vehicle. They are leveraged
    # exclusively for simulation. Take heed!!!
    vehicle.parameters['FS_GCS_ENABLE'] = 0
    vehicle.parameters['FS_EKF_THRESH'] = 100

    def arm_and_takeoff(aTargetAltitude):
        """
        Arms vehicle and fly to aTargetAltitude.
        """

        # Don't let the user try to fly autopilot is booting
        i = 60
        while not vehicle.is_armable and i > 0:
            time.sleep(1)
            i = i - 1
        assert_equals(vehicle.is_armable, True)

        # Copter should arm in GUIDED mode
        vehicle.mode = VehicleMode("GUIDED")
        i = 60
        while vehicle.mode.name != 'GUIDED' and i > 0:
            print(" Waiting for guided %s seconds..." % (i,))
            time.sleep(1)
            i = i - 1
        assert_equals(vehicle.mode.name, 'GUIDED')

        # Arm copter.
        vehicle.armed = True
        i = 60
        while not vehicle.armed and vehicle.mode.name == 'GUIDED' and i > 0:
            print(" Waiting for arming %s seconds..." % (i,))
            time.sleep(1)
            i = i - 1
        assert_equals(vehicle.armed, True)

        # Take off to target altitude
        vehicle.simple_takeoff(aTargetAltitude)

        # Wait until the vehicle reaches a safe height before
        # processing the goto (otherwise the command after
        # Vehicle.simple_takeoff will execute immediately).
        while True:
            print(" Altitude: ", vehicle.location.global_relative_frame.alt)
            # Test for altitude just below target, in case of undershoot.
            if vehicle.location.global_relative_frame.alt >= aTargetAltitude * 0.95:
                print("Reached target altitude")
                break

            assert_equals(vehicle.mode.name, 'GUIDED')
            time.sleep(1)

    arm_and_takeoff(20)

    point1 = LocationGlobalRelative(-35.361354, 149.165218, 20)
    point2 = LocationGlobalRelative(-36.363244, 149.168801, 100)

    for i in range(1, 10000):
        print("Going to first point...")
        vehicle.simple_goto(point1)

        # sleep so we can see the change in map
        time.sleep(30)

        print("Going to second point...")
        vehicle.simple_goto(point2)

        # sleep so we can see the change in map
        time.sleep(30)

    print("Returning to Launch")
    vehicle.mode = VehicleMode("RTL")

    vehicle.close()
