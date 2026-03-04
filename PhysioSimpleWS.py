import socket, struct, asyncio, cv2, json, io, os, uuid
import numpy as np
from datetime import datetime

humerus_position_per = 5
target_image_size = {"shoulder":(540, 350, 3), "prostate":(400,400,3) }

#pose_dict= {1:cv2.imread("pose1.png"),2:cv2.imread("pose2.png"),3:cv2.imread("pose3.png")}
targets = {1:"bicep tendon", 2:"sub", 3:"supra"}
idir = "US_images"
b_size =(540, 350, 3)

async def handle_client(reader, writer):
    now = datetime.now()
    date_time = now.strftime("%d_%m_%Y_%H_%M_%S_%f")
    print(f"client accepted {writer._transport.get_extra_info('peername')} {date_time}")
    connected =True
    while connected:

        now = datetime.now()
        date_time = now.strftime("%d_%m_%Y_%H_%M_%S_%f")
        imagebug=io.BytesIO()
        data = b""
        while data.find(b"ENDOFFILE") == -1:
            data = await reader.read(1024)
            if not data:
                connected = False
                break
            imagebug.write(data)
        if not connected:
            #print("not connected")
            break

        imagebug = imagebug.getvalue().split(b"ENDOFIMAGE")
        pitchbuf = imagebug.pop(-1)
        pitchbuf = pitchbuf.replace(b"ENDOFFILE",b"")
        pitchbuf =  b"{" + pitchbuf.split(b"{")[1]
        pitchbuf  = pitchbuf.split(b"}")[0]  + b"}"
        print(pitchbuf)
        the_data = json.loads(pitchbuf.decode("utf-8"))
        target = the_data["target"]
        print(f"Number of images received: {len(imagebug)}")

        if the_data["imageType"] == "guidance":
            pitch = the_data["pitchy"]
            decoded = cv2.imdecode(np.frombuffer(imagebug[0], np.uint8), -1)
            if decoded is None:
                print(f"Failed to decode guidance image for target {target}")
                writer.write("decode failed".encode())
                continue
            os.makedirs(f"{idir}/guidance", exist_ok=True)
            uuid_str = str(uuid.uuid4())
            filepath = f"{idir}/guidance/{uuid_str}_{pitch}_{target}.jpeg"
            success = cv2.imwrite(filepath, decoded)
            if success:
                print(f"Saved guidance image to {filepath}")
                writer.write("saved".encode())
            else:
                print(f"Failed to save guidance image to {filepath}")
                writer.write("save failed".encode())


        elif the_data["imageType"] == "voxel":
            the_data["timeList"] = json.loads(the_data["timeList"])
            the_data["pitchList"] = json.loads(the_data["pitchList"])
            orientation = the_data.get("orientation", "unknown").lower()
            uuid_str = the_data["pt_number"]
            subfolder = f"{idir}/{uuid_str}/{orientation}"
            os.makedirs(subfolder, exist_ok=True)
            num_images = len(imagebug)
            for x in range(num_images):
                decoded = cv2.imdecode(np.frombuffer(imagebug[x], np.uint8), -1)
                if decoded is None:
                    print(f"Failed to decode voxel image {x} for study {uuid_str}")
                    continue
                pitch = the_data["pitchList"][x] if x < len(the_data["pitchList"]) else 0
                fname = f"{subfolder}/{x}_{pitch}.jpeg"
                success = cv2.imwrite(fname, decoded)
                if not success:
                    print(f"Failed to save voxel image to {fname}")
                else:
                    print(f"Saved voxel image to {fname}")

            with open(f"{idir}/{uuid_str}/data.json", "w") as f:
                json.dump(the_data, f)
            writer.write("all done".encode())
    writer.close()
    #print(f"closed connection {writer._transport.get_extra_info('peername')}")






HOST = "0.0.0.0"
PORT = 8888

async def run_server():
    server = await asyncio.start_server(handle_client, HOST, 8888)
    print(f"server running = {server}")
    async with server:
        await server.serve_forever()

asyncio.run(run_server())
